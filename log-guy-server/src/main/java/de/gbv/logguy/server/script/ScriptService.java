package de.gbv.logguy.server.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.access.AccessDeniedException;

import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.AuditLog;
import de.gbv.logguy.server.config.AppGroups;
import de.gbv.logguy.server.config.AppRegistry;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.script.ScriptRequestException.Reason;
import de.gbv.logguy.server.script.ScriptStore.Draft;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.script.ScriptStore.TestSummary;
import de.gbv.logguy.server.script.ScriptStore.Version;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * Drafts, activation, rollback and reload of scripts, with rights, conflict detection and audit log. GUI, API and MCP
 * all go through this service.
 */
public class ScriptService {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Share of errors in the last test above which activating needs a confirmation. */
    private static final double MAX_ERROR_RATE = 0.01;

    private final LogGuyProperties properties;

    private final AppRegistry appRegistry;

    private final ScriptStore store;

    private final ScriptRuntime runtime;

    private final ParserRegistry parsers;

    private final List<ScriptListener> listeners;

    private final AuditLog audit;

    private final Map<CheckKey, Checked> checked;

    /**
     * Creates the service.
     *
     * @param properties configuration
     * @param appRegistry the configured applications as they are now
     * @param store the script directory
     * @param runtime the script runtime for checks
     * @param parsers the parser scripts
     * @param listeners take over activated scripts of other kinds, e.g. the pseudonymization
     * @param audit the audit log
     */
    public ScriptService(LogGuyProperties properties, AppRegistry appRegistry, ScriptStore store, ScriptRuntime runtime,
        ParserRegistry parsers,
        List<ScriptListener> listeners, AuditLog audit) {
        this.properties = properties;
        this.appRegistry = appRegistry;
        this.store = store;
        this.runtime = runtime;
        this.parsers = parsers;
        this.listeners = List.copyOf(listeners);
        this.audit = audit;
        int cacheSize = properties.scripts().checkCache();
        this.checked = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {

            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<CheckKey, Checked> eldest) {
                return size() > cacheSize;
            }
        });
    }

    /**
     * Records every active script that is not yet the newest version in its history, with the file system as author.
     * Called once at the start of the server.
     *
     * @throws IOException if the history cannot be written
     */
    public void recordStartVersions() throws IOException {
        for (Map.Entry<ScriptId, String> script : store.active().entrySet()) {
            store.recordHistory(script.getKey(), script.getValue(), Actor.FILESYSTEM);
        }
    }

    /**
     * The scripts the user may edit: active files, templates in use and drafts.
     *
     * @param user the user
     * @return the scripts, sorted by kind and name
     * @throws IOException if the script directory cannot be read
     */
    public List<ScriptSummary> list(LogGuyUser user) throws IOException {
        Set<ScriptId> ids = new TreeSet<>(store.active().keySet());
        for (String app : appRegistry.names()) {
            ids.add(new ScriptId(ScriptKind.PARSER, parsers.parserName(app)));
        }
        ids.addAll(Templates.ALWAYS_ACTIVE);
        ids.addAll(store.drafts());
        List<ScriptSummary> result = new ArrayList<>();
        for (ScriptId id : ids) {
            Optional<String> code = activeCode(id);
            Optional<Draft> draft = store.draft(id);
            if (!may(user, id, code.orElse(null), draft.map(Draft::code).orElse(null))) {
                continue;
            }
            result.add(new ScriptSummary(id.kind().dir(), id.name(), origin(id), code.map(ScriptStore::hash)
                .orElse(null), apps(id, code.orElse(null)), draft.map(d -> summary(id, d, code)).orElse(null)));
        }
        return result;
    }

    /**
     * A script with code, draft and history.
     *
     * @param user the user
     * @param id the script
     * @return the details
     * @throws IOException if the script directory cannot be read
     */
    public ScriptDetail get(LogGuyUser user, ScriptId id) throws IOException {
        Optional<String> code = activeCode(id);
        Optional<Draft> draft = store.draft(id);
        if (code.isEmpty() && draft.isEmpty()) {
            throw new ScriptRequestException(Reason.NOT_FOUND, "Skript " + id + " gibt es nicht.");
        }
        require(user, id, code.orElse(null), draft.map(Draft::code).orElse(null));
        DraftView draftView = draft.map(d -> new DraftView(d.code(), summary(id, d, code))).orElse(null);
        return new ScriptDetail(id.kind().dir(), id.name(), origin(id), code.orElse(null),
            code.map(ScriptStore::hash).orElse(null), apps(id, code.orElse(null)), draftView, store.history(id));
    }

    /**
     * The code of an earlier version, e.g. to compare it with the active one.
     *
     * @param user the user
     * @param id the script
     * @param at the time of the version, as listed in the history
     * @return the code
     * @throws IOException if the history cannot be read
     */
    public String historyCode(LogGuyUser user, ScriptId id, Instant at) throws IOException {
        String code = store.historyCode(id, at).orElseThrow(() -> new ScriptRequestException(Reason.NOT_FOUND,
            "Eine Fassung von " + id + " vom " + at + " gibt es nicht."));
        require(user, id, activeCode(id).orElse(null), code);
        return code;
    }

    /**
     * Saves a draft. It is not active until {@link #activate} is called.
     *
     * @param caller who saves
     * @param id the script
     * @param code the code
     * @param base hash of the version the draft starts from, {@code null} for the active version now
     * @return the script with the new draft
     * @throws IOException if the draft cannot be written
     */
    public ScriptDetail saveDraft(Caller caller, ScriptId id, String code, String base) throws IOException {
        if (code == null || code.isBlank()) {
            throw new ScriptRequestException(Reason.INVALID, "Der Entwurf ist leer.");
        }
        long size = code.getBytes(StandardCharsets.UTF_8).length;
        if (size > properties.scripts().maxSize().toBytes()) {
            throw new ScriptRequestException(Reason.INVALID, "Das Skript ist größer als "
                + properties.scripts().maxSize().toKilobytes() + " KB.");
        }
        Optional<String> active = activeCode(id);
        require(caller.user(), id, active.orElse(null), code);
        String startedFrom = base != null ? base : active.map(ScriptStore::hash).orElse(null);
        store.saveDraft(id, code, startedFrom, caller.actor());
        audit.log(caller.actor(), "script.draft", id.toString(), Map.of("hash", ScriptStore.hash(code)));
        return get(caller.user(), id);
    }

    /**
     * Removes a draft.
     *
     * @param caller who removes it
     * @param id the script
     * @throws IOException if the draft cannot be deleted
     */
    public void discardDraft(Caller caller, ScriptId id) throws IOException {
        Draft draft = store.draft(id)
            .orElseThrow(() -> new ScriptRequestException(Reason.NOT_FOUND, "Zu " + id + " gibt es keinen Entwurf."));
        require(caller.user(), id, activeCode(id).orElse(null), draft.code());
        store.deleteDraft(id);
        audit.log(caller.actor(), "script.discard", id.toString(), Map.of());
    }

    /**
     * The code a test runs: the given code, otherwise the draft, otherwise the active version.
     *
     * @param user the user, needs the right to edit the script
     * @param id the script
     * @param code code sent with the test, may be {@code null}
     * @param active test the active version even if there is a draft
     * @return the code
     * @throws IOException if the script directory cannot be read
     */
    public String codeForTest(LogGuyUser user, ScriptId id, String code, boolean active) throws IOException {
        Optional<String> current = activeCode(id);
        Optional<String> draft = store.draft(id).map(Draft::code);
        String tested = code != null ? code : active ? current.orElse(null) : draft.or(() -> current).orElse(null);
        if (tested == null) {
            throw new ScriptRequestException(Reason.NOT_FOUND, "Skript " + id + " gibt es nicht.");
        }
        require(user, id, current.orElse(null), tested);
        return tested;
    }

    /**
     * Stores the result of a test with the draft, so the approval can see it.
     *
     * @param id the script
     * @param code the tested code
     * @param summary the result
     */
    public void recordTest(ScriptId id, String code, TestSummary summary) {
        try {
            store.setDraftTest(id, code, summary);
        } catch (IOException e) {
            LOGGER.warn("cannot store test result of {}", id, e);
        }
    }

    /**
     * Activates the draft of a script.
     *
     * @param caller who activates
     * @param id the script
     * @param force activate even if the file changed since the draft was started
     * @param confirm activate even if the last test had more than 1 % errors
     * @return the activated script
     * @throws IOException if the files cannot be written
     */
    public ScriptDetail activate(Caller caller, ScriptId id, boolean force, boolean confirm) throws IOException {
        Draft draft = store.draft(id)
            .orElseThrow(() -> new ScriptRequestException(Reason.NOT_FOUND, "Zu " + id + " gibt es keinen Entwurf."));
        Optional<String> active = activeCode(id);
        require(caller.user(), id, active.orElse(null), draft.code());
        requireActivation(caller, id);
        String current = currentHash(id);
        if (!force && current != null && !current.equals(draft.meta().base())) {
            throw new ScriptRequestException(Reason.CONFLICT, "Die aktive Fassung von " + id
                + " hat sich geändert, seit der Entwurf begonnen wurde.", details("current", current));
        }
        TestSummary test = draft.meta().test();
        if (!confirm && test != null && test.errorRate() > MAX_ERROR_RATE) {
            throw new ScriptRequestException(Reason.CONFIRM, String.format(Locale.GERMANY,
                "Im letzten Test hatten %.1f %% der Events Fehler. Aktivieren nur mit Bestätigung.",
                test.errorRate() * 100), details("errorRate", test.errorRate()));
        }
        activateCode(caller.actor(), id, draft.code());
        store.deleteDraft(id);
        audit.log(caller.actor(), "script.activate", id.toString(), Map.of("hash", ScriptStore.hash(draft.code()),
            "force", force, "confirm", confirm));
        return get(caller.user(), id);
    }

    /**
     * Activates a version from the history again.
     *
     * @param caller who rolls back
     * @param id the script
     * @param at the time of the version
     * @return the activated script
     * @throws IOException if the files cannot be read or written
     */
    public ScriptDetail rollback(Caller caller, ScriptId id, Instant at) throws IOException {
        String code = store.historyCode(id, at).orElseThrow(() -> new ScriptRequestException(Reason.NOT_FOUND,
            "Eine Fassung von " + id + " vom " + at + " gibt es nicht."));
        require(caller.user(), id, activeCode(id).orElse(null), code);
        requireActivation(caller, id);
        activateCode(caller.actor(), id, code);
        audit.log(caller.actor(), "script.rollback", id.toString(), Map.of("at", at.toString(), "hash",
            ScriptStore.hash(code)));
        return get(caller.user(), id);
    }

    /**
     * Removes a script. Parsers cannot be removed.
     *
     * @param caller who removes it
     * @param id the script
     * @throws IOException if the file cannot be deleted
     */
    public void delete(Caller caller, ScriptId id) throws IOException {
        if (!id.kind().deletable()) {
            throw new ScriptRequestException(Reason.INVALID, "Parser lassen sich nicht entfernen.");
        }
        String code = store.active(id).orElseThrow(() -> new ScriptRequestException(Reason.NOT_FOUND,
            "Skript " + id + " gibt es nicht."));
        require(caller.user(), id, code, null);
        requireActivation(caller, id);
        store.delete(id);
        notifyListeners(id.kind());
        audit.log(caller.actor(), "script.delete", id.toString(), Map.of("hash", ScriptStore.hash(code)));
    }

    /**
     * Checks a script of any kind. The module is always evaluated again, e.g. before it is activated.
     *
     * @param id the script
     * @param code the code
     * @return the error, empty if the script is fine
     */
    public Optional<String> check(ScriptId id, String code) {
        return Optional.ofNullable(evaluate(id, code).error());
    }

    // the result of an earlier check of the same code, so listing scripts does not evaluate every draft again
    private Checked checked(ScriptId id, String code) {
        Checked result = checked.get(new CheckKey(id, ScriptStore.hash(code)));
        return result != null ? result : evaluate(id, code);
    }

    private Checked evaluate(ScriptId id, String code) {
        Checked result;
        try {
            if (id.kind() == ScriptKind.PARSER) {
                ParserScript.load(runtime, id.name(), code);
                result = new Checked(List.of(), null);
            } else {
                result = new Checked(ModuleInfo.check(runtime, id.kind(), id.name(), code).apps(), null);
            }
        } catch (ScriptException e) {
            result = new Checked(List.of(), e.getMessage());
        }
        checked.put(new CheckKey(id, ScriptStore.hash(code)), result);
        return result;
    }

    /**
     * Reads the script directory again and activates what changed on disk, after checking all of it. Nothing is
     * activated if one script has errors.
     *
     * @param only only this script, {@code null} for all
     * @return the changed scripts and the errors
     * @throws IOException if the directory cannot be read
     */
    public ReloadResult reload(ScriptId only) throws IOException {
        return reload(only, appRegistry.apps());
    }

    /**
     * Like {@link #reload(ScriptId)}, but also takes over the applications of a reloaded configuration. Their parsers
     * are checked with the scripts, and nothing changes if one has errors. Afterwards new applications are indexed
     * and searched like the others.
     *
     * @param only only this script, {@code null} for all
     * @param configured the applications from now on
     * @return the changed scripts and the errors
     * @throws IOException if the directory cannot be read
     */
    public synchronized ReloadResult reload(ScriptId only, Map<String, AppProperties> configured)
        throws IOException {
        Map<ScriptId, String> disk;
        if (only == null) {
            disk = store.readDisk();
        } else {
            disk = new HashMap<>(store.active());
            Optional<String> code = store.readDisk(only);
            if (code.isPresent()) {
                disk.put(only, code.get());
            } else {
                disk.remove(only);
            }
        }
        Map<ScriptId, String> changes = new TreeMap<>();
        for (Map.Entry<ScriptId, String> script : disk.entrySet()) {
            if (!script.getValue().equals(store.active().get(script.getKey()))) {
                changes.put(script.getKey(), script.getValue());
            }
        }
        for (ScriptId id : store.active().keySet()) {
            if (!disk.containsKey(id)) {
                changes.put(id, null);
            }
        }
        List<String> errors = new ArrayList<>();
        for (Map.Entry<ScriptId, String> change : changes.entrySet()) {
            if (change.getValue() != null) {
                check(change.getKey(), change.getValue()).ifPresent(errors::add);
            }
        }
        ParserRegistry.Prepared prepared = parsers.prepare(disk, configured);
        boolean appsChanged = !configured.equals(appRegistry.apps());
        errors.addAll(prepared.errors());
        List<String> changed = changes.keySet().stream().map(ScriptId::toString).toList();
        if (!errors.isEmpty()) {
            return new ReloadResult(changed, List.copyOf(errors));
        }
        store.setActive(changes);
        for (Map.Entry<ScriptId, String> change : changes.entrySet()) {
            if (change.getValue() != null) {
                store.recordHistory(change.getKey(), change.getValue(), Actor.FILESYSTEM);
            }
        }
        if (appsChanged) {
            appRegistry.update(configured);
            LOGGER.info("applications are now {}", appRegistry.names());
        }
        if (appsChanged || changes.keySet().stream().anyMatch(id -> id.kind() == ScriptKind.PARSER)) {
            parsers.apply(prepared);
        }
        changes.keySet().stream().map(ScriptId::kind).filter(kind -> kind != ScriptKind.PARSER).distinct()
            .forEach(this::notifyListeners);
        if (!changes.isEmpty()) {
            audit.log(Actor.FILESYSTEM, "script.reload", only == null ? "*" : only.toString(),
                Map.of("changed", changed));
        }
        return new ReloadResult(changed, List.of());
    }

    /**
     * The application patterns a script affects.
     *
     * @param id the script
     * @param code its code, may be {@code null}
     * @return the applications of a parser, {@code meta.apps} of other kinds, empty if unknown
     */
    public List<String> apps(ScriptId id, String code) {
        if (id.kind() == ScriptKind.PARSER) {
            return List.copyOf(parsers.appsUsing(id.name()));
        }
        if (code == null) {
            return List.of();
        }
        return checked(id, code).apps();
    }

    /**
     * Whether the user may see and edit a script, judged by the active code and the draft. Scripts with {@code *}
     * or an application group in {@code meta.apps} are for administrators only, because the applications they
     * cover change with the configuration. A user without {@link Right#SCRIPTS} for any application is turned away
     * before any code is evaluated. {@link Right#READ} is needed in plain, {@link Right#READ_PSEUDONYMIZED} is not
     * enough, also over MCP: a script sees the plain lines and could hand them out in its results.
     *
     * @param user the user
     * @param id the script
     * @param active the active code, may be {@code null}
     * @param draft the draft, may be {@code null}
     * @return true if allowed
     */
    public boolean may(LogGuyUser user, ScriptId id, String active, String draft) {
        if (!user.hasRight(Right.SCRIPTS)) {
            return false;
        }
        if (id.kind() == ScriptKind.PSEUDONYM) {
            return user.isAdmin();
        }
        Set<String> patterns = new TreeSet<>();
        if (id.kind() == ScriptKind.PARSER) {
            patterns.addAll(parsers.appsUsing(id.name()));
        } else {
            for (String code : new String[] { active, draft }) {
                if (code != null) {
                    List<String> apps = apps(id, code);
                    if (apps.isEmpty()) {
                        return user.isAdmin();
                    }
                    patterns.addAll(apps);
                }
            }
        }
        if (patterns.isEmpty()
            || patterns.stream().anyMatch(pattern -> pattern.contains("*") || AppGroups.isGroup(pattern))) {
            return user.isAdmin();
        }
        return patterns.stream().allMatch(app -> user.may(Right.SCRIPTS, app) && user.may(Right.READ, app));
    }

    private void require(LogGuyUser user, ScriptId id, String active, String draft) {
        if (!may(user, id, active, draft)) {
            throw new AccessDeniedException("no script right for " + id);
        }
    }

    private static void requireActivation(Caller caller, ScriptId id) {
        if (caller.actor().via() != Actor.Via.MCP) {
            return;
        }
        boolean allowed = id.kind() == ScriptKind.PARSER || id.kind() == ScriptKind.RULE;
        if (!caller.activate() || !allowed) {
            throw new AccessDeniedException("MCP may not activate " + id);
        }
    }

    private void activateCode(Actor actor, ScriptId id, String code) throws IOException {
        check(id, code).ifPresent(error -> {
            throw new ScriptRequestException(Reason.INVALID, "Das Skript ist fehlerhaft: " + error);
        });
        ParserRegistry.Prepared prepared = null;
        if (id.kind() == ScriptKind.PARSER) {
            Map<ScriptId, String> scripts = new HashMap<>(store.active());
            scripts.put(id, code);
            prepared = parsers.prepare(scripts);
            if (!prepared.errors().isEmpty()) {
                throw new ScriptRequestException(Reason.INVALID, "Das Skript ist fehlerhaft: "
                    + String.join("; ", prepared.errors()));
            }
        }
        store.write(id, code, actor);
        if (prepared != null) {
            parsers.apply(prepared);
        } else {
            notifyListeners(id.kind());
        }
        LOGGER.info("activated {} {} by {}", id, ScriptStore.hash(code), actor.label());
    }

    private void notifyListeners(ScriptKind kind) {
        for (ScriptListener listener : listeners) {
            listener.changed(kind, store);
        }
    }

    private Optional<String> activeCode(ScriptId id) throws IOException {
        Optional<String> code = store.active(id);
        if (code.isEmpty() && usesTemplate(id)) {
            return Optional.of(Templates.read(id));
        }
        return code;
    }

    private String currentHash(ScriptId id) throws IOException {
        Optional<String> disk = store.readDisk(id);
        if (disk.isPresent()) {
            return ScriptStore.hash(disk.get());
        }
        return activeCode(id).map(ScriptStore::hash).orElse(null);
    }

    private static boolean usesTemplate(ScriptId id) {
        return (id.kind() == ScriptKind.PARSER || Templates.ALWAYS_ACTIVE.contains(id)) && Templates.has(id);
    }

    private String origin(ScriptId id) {
        if (store.active(id).isPresent()) {
            return "file";
        }
        if (usesTemplate(id)) {
            return "template";
        }
        return "draft";
    }

    private DraftSummary summary(ScriptId id, Draft draft, Optional<String> active) {
        String current = active.map(ScriptStore::hash).orElse(null);
        boolean conflict = current != null && !current.equals(draft.meta().base());
        return new DraftSummary(ScriptStore.hash(draft.code()), draft.meta().base(), conflict, draft.meta().user(),
            draft.meta().token(), draft.meta().via(), draft.meta().updated(), draft.meta().test(),
            checked(id, draft.code()).error());
    }

    private static Map<String, Object> details(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    /**
     * Whether the code is the active version of a script that a human approved: activated in GUI or API, from the
     * file system or a template, but not by an MCP token with {@code activate: true}.
     *
     * @param id the script
     * @param code the code
     * @return true if the code is active and was not activated over MCP
     * @throws IOException if the script directory cannot be read
     */
    public boolean isApprovedActive(ScriptId id, String code) throws IOException {
        Optional<String> active = activeCode(id);
        if (active.isEmpty() || !active.get().equals(code)) {
            return false;
        }
        String hash = ScriptStore.hash(code);
        return store.history(id).stream().filter(version -> hash.equals(version.hash())).findFirst()
            .map(version -> version.via() != Actor.Via.MCP).orElse(true);
    }

    private record CheckKey(ScriptId id, String hash) {
    }

    private record Checked(List<String> apps, String error) {
    }

    /**
     * Who calls the service.
     *
     * @param user the user with the rights
     * @param actor the user, token and way for audit and history
     * @param activate whether an MCP token may activate parsers and rules
     */
    public record Caller(LogGuyUser user, Actor actor, boolean activate) {
    }

    /**
     * A script in the list.
     *
     * @param kind the kind
     * @param name the name
     * @param origin {@code file}, {@code template} or {@code draft} for a new script without active version
     * @param hash hash of the active version
     * @param apps the applications it affects
     * @param draft the open draft, if any
     */
    public record ScriptSummary(String kind, String name, String origin, String hash, List<String> apps,
        DraftSummary draft) {
    }

    /**
     * A script with everything the editor needs.
     *
     * @param kind the kind
     * @param name the name
     * @param origin {@code file}, {@code template} or {@code draft}
     * @param code the active code, {@code null} for a new script
     * @param hash hash of the active code
     * @param apps the applications it affects
     * @param draft the open draft, if any
     * @param history the activated versions, newest first
     */
    public record ScriptDetail(String kind, String name, String origin, String code, String hash, List<String> apps,
        DraftView draft, List<Version> history) {
    }

    /**
     * A draft with its code.
     *
     * @param code the code
     * @param info hash, author and test
     */
    public record DraftView(String code, DraftSummary info) {
    }

    /**
     * What is known about a draft.
     *
     * @param hash hash of the draft
     * @param base hash of the version it started from
     * @param conflict whether the active version changed since
     * @param user who saved it
     * @param token the token used
     * @param via the way
     * @param updated when it was saved
     * @param test the last test of this code
     * @param error the check error of the draft, {@code null} if it is fine
     */
    public record DraftSummary(String hash, String base, boolean conflict, String user, String token, Actor.Via via,
        Instant updated, TestSummary test, String error) {
    }

    /**
     * Result of a reload.
     *
     * @param changed the scripts that changed on disk
     * @param errors the errors, nothing was activated if not empty
     */
    public record ReloadResult(List<String> changed, List<String> errors) {
    }
}
