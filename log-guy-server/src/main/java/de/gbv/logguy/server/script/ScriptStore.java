package de.gbv.logguy.server.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import de.gbv.logguy.server.audit.Actor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The script directory: the active scripts in {@code <kind>/<name>.js}, every activated version in
 * {@code .history/<kind>/<name>/} and the drafts in {@code .drafts/<kind>/}.
 * <p>
 * The active scripts are held in memory and change only by {@link #write}, {@link #delete} or {@link #setActive},
 * so a file edited on disk takes effect with the next reload, like with nginx. Files are replaced atomically by
 * {@code rename}.
 */
public class ScriptStore {

    /** Allowed script names, also used as file names. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}(\\.[A-Za-z0-9_-]+)*");

    private static final String SUFFIX = ".js";

    private static final String META_SUFFIX = ".json";

    private final Path dir;

    private final Clock clock;

    private final JsonMapper json;

    private final AtomicReference<Map<ScriptId, String>> active = new AtomicReference<>(Map.of());

    /**
     * Creates the store. Nothing is read before {@link #scan()}.
     *
     * @param dir the script directory
     * @param clock clock for history and drafts
     * @param json mapper for the meta files
     */
    public ScriptStore(Path dir, Clock clock, JsonMapper json) {
        this.dir = dir;
        this.clock = clock;
        this.json = json;
    }

    /**
     * The script directory.
     *
     * @return the directory
     */
    public Path dir() {
        return dir;
    }

    /**
     * Reads all scripts from disk and makes them the active ones.
     *
     * @return the scripts read
     * @throws IOException if the directory cannot be read
     */
    public Map<ScriptId, String> scan() throws IOException {
        Map<ScriptId, String> scripts = readDisk();
        active.set(Map.copyOf(scripts));
        return scripts;
    }

    /**
     * Reads all scripts from disk without changing the active ones.
     *
     * @return the scripts on disk
     * @throws IOException if the directory cannot be read
     */
    public Map<ScriptId, String> readDisk() throws IOException {
        Map<ScriptId, String> scripts = new HashMap<>();
        for (ScriptKind kind : ScriptKind.values()) {
            Path kindDir = dir.resolve(kind.dir());
            if (!Files.isDirectory(kindDir)) {
                continue;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(kindDir, "*" + SUFFIX)) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    name = name.substring(0, name.length() - SUFFIX.length());
                    if (NAME.matcher(name).matches() && Files.isRegularFile(file)) {
                        scripts.put(new ScriptId(kind, name), Files.readString(file));
                    }
                }
            }
        }
        return scripts;
    }

    /**
     * Reads one script from disk.
     *
     * @param id the script
     * @return the code, empty if there is no file
     * @throws IOException if the file cannot be read
     */
    public Optional<String> readDisk(ScriptId id) throws IOException {
        Path file = file(id);
        return Files.isRegularFile(file) ? Optional.of(Files.readString(file)) : Optional.empty();
    }

    /**
     * The active code of a script.
     *
     * @param id the script
     * @return the code, empty if not active
     */
    public Optional<String> active(ScriptId id) {
        return Optional.ofNullable(active.get().get(id));
    }

    /**
     * All active scripts.
     *
     * @return the scripts by id
     */
    public Map<ScriptId, String> active() {
        return active.get();
    }

    /**
     * Replaces the active code of some scripts in memory, after they were checked.
     *
     * @param changes new code by script, {@code null} removes a script
     */
    public synchronized void setActive(Map<ScriptId, String> changes) {
        Map<ScriptId, String> scripts = new HashMap<>(active.get());
        changes.forEach((id, code) -> {
            if (code == null) {
                scripts.remove(id);
            } else {
                scripts.put(id, code);
            }
        });
        active.set(Map.copyOf(scripts));
    }

    /**
     * Writes a checked script to disk, records it in the history and makes it active.
     *
     * @param id the script
     * @param code the new code
     * @param actor who activates it
     * @throws IOException if the file cannot be written
     */
    public void write(ScriptId id, String code, Actor actor) throws IOException {
        Path file = file(id);
        Files.createDirectories(file.getParent());
        replace(file, code);
        recordHistory(id, code, actor);
        Map<ScriptId, String> change = new HashMap<>();
        change.put(id, code);
        setActive(change);
    }

    /**
     * Removes a script from disk and from the active ones. The history keeps it.
     *
     * @param id the script
     * @throws IOException if the file cannot be deleted
     */
    public void delete(ScriptId id) throws IOException {
        Files.deleteIfExists(file(id));
        Map<ScriptId, String> change = new HashMap<>();
        change.put(id, null);
        setActive(change);
    }

    /**
     * Adds a version to the history unless it is already the newest entry.
     *
     * @param id the script
     * @param code the code
     * @param actor who activated it
     * @throws IOException if the history cannot be written
     */
    public synchronized void recordHistory(ScriptId id, String code, Actor actor) throws IOException {
        String hash = hash(code);
        List<Version> versions = history(id);
        if (!versions.isEmpty() && versions.getFirst().hash().equals(hash)) {
            return;
        }
        Path historyDir = historyDir(id);
        Files.createDirectories(historyDir);
        long millis = clock.millis();
        if (!versions.isEmpty()) {
            millis = Math.max(millis, versions.getFirst().at().toEpochMilli() + 1);
        }
        while (Files.exists(historyDir.resolve(millis + SUFFIX))) {
            millis++;
        }
        Instant at = Instant.ofEpochMilli(millis);
        Version version = new Version(at, actor.user(), actor.token(), actor.via(), hash);
        replace(historyDir.resolve(millis + META_SUFFIX), json.writeValueAsString(version));
        replace(historyDir.resolve(millis + SUFFIX), code);
    }

    /**
     * The activated versions of a script, newest first.
     *
     * @param id the script
     * @return the versions
     * @throws IOException if the history cannot be read
     */
    public List<Version> history(ScriptId id) throws IOException {
        Path historyDir = historyDir(id);
        if (!Files.isDirectory(historyDir)) {
            return List.of();
        }
        List<Version> versions = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(historyDir, "*" + META_SUFFIX)) {
            for (Path file : files) {
                versions.add(json.readValue(Files.readAllBytes(file), Version.class));
            }
        }
        versions.sort(Comparator.comparing(Version::at).reversed());
        return versions;
    }

    /**
     * The code of a version from the history.
     *
     * @param id the script
     * @param at the time of the version
     * @return the code, empty if there is no such version
     * @throws IOException if the file cannot be read
     */
    public Optional<String> historyCode(ScriptId id, Instant at) throws IOException {
        Path file = historyDir(id).resolve(at.toEpochMilli() + SUFFIX);
        return Files.isRegularFile(file) ? Optional.of(Files.readString(file)) : Optional.empty();
    }

    /**
     * The draft of a script.
     *
     * @param id the script
     * @return the draft, empty if there is none
     * @throws IOException if the draft cannot be read
     */
    public Optional<Draft> draft(ScriptId id) throws IOException {
        Path code = draftFile(id, SUFFIX);
        Path meta = draftFile(id, META_SUFFIX);
        if (!Files.isRegularFile(code) || !Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        DraftMeta info = json.readValue(Files.readAllBytes(meta), DraftMeta.class);
        return Optional.of(new Draft(Files.readString(code), info));
    }

    /**
     * Saves a draft. The test result of an earlier draft is dropped, because it belongs to other code.
     *
     * @param id the script
     * @param code the code of the draft
     * @param base hash of the version the draft started from, {@code null} for a new script
     * @param actor who saves it
     * @return the saved draft
     * @throws IOException if the draft cannot be written
     */
    public synchronized Draft saveDraft(ScriptId id, String code, String base, Actor actor) throws IOException {
        Path file = draftFile(id, SUFFIX);
        Files.createDirectories(file.getParent());
        DraftMeta meta = new DraftMeta(base, actor.user(), actor.token(), actor.via(), clock.instant(), null);
        replace(draftFile(id, META_SUFFIX), json.writeValueAsString(meta));
        replace(file, code);
        return new Draft(code, meta);
    }

    /**
     * Stores the result of the last test with the draft, if the draft still has the tested code.
     *
     * @param id the script
     * @param code the tested code
     * @param summary the result
     * @throws IOException if the draft cannot be written
     */
    public synchronized void setDraftTest(ScriptId id, String code, TestSummary summary) throws IOException {
        Optional<Draft> draft = draft(id);
        if (draft.isPresent() && draft.get().code().equals(code)) {
            DraftMeta meta = draft.get().meta();
            replace(draftFile(id, META_SUFFIX), json.writeValueAsString(new DraftMeta(meta.base(), meta.user(),
                meta.token(), meta.via(), meta.updated(), summary)));
        }
    }

    /**
     * Removes a draft.
     *
     * @param id the script
     * @throws IOException if the files cannot be deleted
     */
    public synchronized void deleteDraft(ScriptId id) throws IOException {
        Files.deleteIfExists(draftFile(id, SUFFIX));
        Files.deleteIfExists(draftFile(id, META_SUFFIX));
    }

    /**
     * All scripts with a draft.
     *
     * @return the ids, sorted
     * @throws IOException if the directory cannot be read
     */
    public List<ScriptId> drafts() throws IOException {
        TreeSet<ScriptId> ids = new TreeSet<>();
        for (ScriptKind kind : ScriptKind.values()) {
            Path kindDir = dir.resolve(".drafts").resolve(kind.dir());
            if (!Files.isDirectory(kindDir)) {
                continue;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(kindDir, "*" + SUFFIX)) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    ids.add(new ScriptId(kind, name.substring(0, name.length() - SUFFIX.length())));
                }
            }
        }
        return List.copyOf(ids);
    }

    /**
     * A short hash of a script, used to detect changes and conflicts.
     *
     * @param code the code
     * @return 12 hex digits of SHA-256
     */
    public static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path file(ScriptId id) {
        return dir.resolve(id.kind().dir()).resolve(id.name() + SUFFIX);
    }

    private Path historyDir(ScriptId id) {
        return dir.resolve(".history").resolve(id.kind().dir()).resolve(id.name());
    }

    private Path draftFile(ScriptId id, String suffix) {
        return dir.resolve(".drafts").resolve(id.kind().dir()).resolve(id.name() + suffix);
    }

    private static void replace(Path file, String content) throws IOException {
        Path temp = file.resolveSibling("." + file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Identifies a script.
     *
     * @param kind the kind
     * @param name the name, without {@code .js}
     */
    public record ScriptId(ScriptKind kind, String name) implements Comparable<ScriptId> {

        /**
         * Checks the name.
         */
        public ScriptId {
            if (kind == null || name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid script name " + name);
            }
        }

        /**
         * Parses {@code kind/name}.
         *
         * @param id e.g. {@code parser/digibib}
         * @return the id
         * @throws IllegalArgumentException if the id is invalid
         */
        public static ScriptId parse(String id) {
            int slash = id.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("invalid script id " + id);
            }
            return new ScriptId(ScriptKind.of(id.substring(0, slash)), id.substring(slash + 1));
        }

        @Override
        public int compareTo(ScriptId other) {
            int result = kind.compareTo(other.kind);
            return result != 0 ? result : name.compareTo(other.name);
        }

        @Override
        public String toString() {
            return kind.dir() + "/" + name;
        }
    }

    /**
     * An activated version in the history.
     *
     * @param at when it was activated
     * @param user who activated it, {@code null} for the file system
     * @param token the token used, if any
     * @param via the way
     * @param hash hash of the code
     */
    public record Version(Instant at, String user, String token, Actor.Via via, String hash) {
    }

    /**
     * A draft with its meta data.
     *
     * @param code the code
     * @param meta who saved it, the version it started from and the last test
     */
    public record Draft(String code, DraftMeta meta) {
    }

    /**
     * Meta data of a draft.
     *
     * @param base hash of the version the draft started from, {@code null} for a new script
     * @param user who saved it
     * @param token the token used, if any
     * @param via the way
     * @param updated when it was saved
     * @param test the result of the last test of exactly this code, {@code null} if not tested
     */
    public record DraftMeta(String base, String user, String token, Actor.Via via, Instant updated,
        TestSummary test) {
    }

    /**
     * Short result of a test, kept with the draft for the approval.
     *
     * @param at when the test ran
     * @param source the test source, e.g. {@code app digibib, 1000 lines}
     * @param events events or lines tested
     * @param errors events or lines with errors
     */
    public record TestSummary(Instant at, String source, int events, int errors) {

        /**
         * Share of errors.
         *
         * @return between 0 and 1
         */
        public double errorRate() {
            return events == 0 ? 0 : (double) errors / events;
        }
    }
}
