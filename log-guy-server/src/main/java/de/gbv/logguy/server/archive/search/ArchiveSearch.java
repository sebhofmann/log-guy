package de.gbv.logguy.server.archive.search;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

import de.gbv.logguy.server.archive.ArchiveFile;
import de.gbv.logguy.server.archive.ArchiveFrame;
import de.gbv.logguy.server.archive.ArchiveMeta;
import de.gbv.logguy.server.archive.ArchiveStore;
import de.gbv.logguy.server.archive.search.ArchiveSearchJob.Owner;
import de.gbv.logguy.server.archive.search.ArchiveSearchJob.State;
import de.gbv.logguy.server.config.AppRegistry;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.ArchiveSearchProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.search.PseudonymizedSearch;
import de.gbv.logguy.server.search.SearchException;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * The archive search: searches the lines in {@code archive/} like {@code zgrep}, as a job in the background. The time
 * range selects the files by their meta data and the frames by their index. The frames are decompressed and searched
 * in parallel, a regular expression runs with RE2/J in linear time.
 * <p>
 * Pseudonymized jobs match the expression against the real lines with the pseudonyms of the query resolved, and keep
 * a hit only if the query also matches the pseudonymized line. A search for a real value that is pseudonymized thus
 * finds nothing and does not reveal whether the value occurs.
 */
public class ArchiveSearch implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final java.util.regex.Pattern REGEX_PSEUDONYM = java.util.regex.Pattern
        .compile("\\\\?(" + Pseudonyms.PSEUDONYM.pattern() + ")");

    private final LogGuyProperties properties;

    private final AppRegistry appRegistry;

    private final ArchiveSearchProperties settings;

    private final ArchiveStore archive;

    private final PseudonymService pseudonyms;

    private final Clock clock;

    private final ExecutorService workers;

    private final Map<String, ArchiveSearchJob> jobs = new ConcurrentHashMap<>();

    /**
     * Creates the search with its worker threads.
     *
     * @param properties configuration, for the applications and the limits
     * @param appRegistry the configured applications as they are now
     * @param archive the archive
     * @param pseudonyms pseudonymizes the hits of pseudonymized jobs
     * @param clock clock for the limits
     */
    public ArchiveSearch(LogGuyProperties properties, AppRegistry appRegistry, ArchiveStore archive,
        PseudonymService pseudonyms,
        Clock clock) {
        this.properties = properties;
        this.appRegistry = appRegistry;
        this.settings = properties.archive().search();
        this.archive = archive;
        this.pseudonyms = pseudonyms;
        this.clock = clock;
        AtomicInteger number = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(settings.threads(), runnable -> {
            Thread thread = new Thread(runnable, "archive-search-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The applications a user may search in the archive: with the right {@code archive} and the right to read them,
     * pseudonymized at least.
     *
     * @param user the user
     * @return the applications, sorted
     */
    public Set<String> apps(LogGuyUser user) {
        LogGuyUser view = user.asPseudonymized();
        Set<String> known = new TreeSet<>(appRegistry.names());
        archive.files().forEach(file -> known.add(file.meta().app()));
        Set<String> allowed = new TreeSet<>();
        for (String app : known) {
            if (user.may(Right.ARCHIVE, app) && view.mayRead(app)) {
                allowed.add(app);
            }
        }
        return allowed;
    }

    /**
     * The hosts that delivered files of the applications.
     *
     * @param apps the applications
     * @return the hosts, sorted
     */
    public Set<String> hosts(Set<String> apps) {
        Set<String> hosts = new TreeSet<>();
        archive.files().forEach(file -> {
            if (apps.contains(file.meta().app())) {
                hosts.add(file.meta().host());
            }
        });
        return hosts;
    }

    /**
     * Starts a job.
     *
     * @param user the user, a pseudonymized user always gets pseudonymized hits
     * @param owner who may read the job
     * @param session pseudonyms for the hits and the query, used if the hits are pseudonymized
     * @param request what to search
     * @return the started job
     * @throws SearchException if the request is invalid or a limit is exceeded
     */
    public ArchiveSearchJob start(LogGuyUser user, Owner owner, Pseudonyms session, ArchiveSearchRequest request) {
        cleanUp();
        String query = request.query();
        if (query == null || query.isBlank()) {
            throw new SearchException("Ein Suchausdruck fehlt.");
        }
        Set<String> allowed = apps(user);
        Set<String> apps = new TreeSet<>();
        if (request.apps().isEmpty()) {
            apps.addAll(allowed);
        } else {
            for (String app : request.apps()) {
                if (!allowed.contains(app)) {
                    throw new SearchException("Die Anwendung „" + app
                        + "“ gibt es nicht oder sie ist für die Archivsuche nicht freigegeben.");
                }
                apps.add(app);
            }
        }
        if (apps.isEmpty()) {
            throw new SearchException("Für die Archivsuche ist keine Anwendung freigegeben.");
        }
        boolean pseudonymized = user.pseudonymized() || !apps.stream().allMatch(user::readsPlain);
        Instant now = clock.instant();
        Instant to = request.to() == null ? now : request.to();
        Instant from = request.from() == null ? to.minus(settings.defaultRange()) : request.from();
        if (!from.isBefore(to)) {
            throw new SearchException("Der Beginn muss vor dem Ende liegen.");
        }
        if (Duration.between(from, to).compareTo(settings.maxRange()) > 0) {
            throw new SearchException("Der Zeitraum ist länger als erlaubt, höchstens " + settings.maxRange().toDays()
                + " Tage.");
        }
        int context = request.context() == null ? settings.contextLines() : request.context();
        if (context < 0 || context > settings.maxContextLines()) {
            throw new SearchException("Kontext ist höchstens " + settings.maxContextLines() + " Zeilen.");
        }
        LineMatcher raw;
        LineMatcher check = null;
        if (!pseudonymized) {
            raw = matcher(query, request);
        } else if (request.regex()) {
            raw = matcher(pseudonymsInRegex(query, p -> Pattern.quote(session.real(p).orElse(p))), request);
            check = matcher(pseudonymsInRegex(query, Pattern::quote), request);
        } else {
            raw = matcher(session.resolve(query, s -> s), request);
            check = matcher(query, request);
        }
        long running = jobs.values().stream()
            .filter(job -> job.owner().user().equals(owner.user()) && job.running()).count();
        if (running >= settings.maxJobs()) {
            throw new SearchException("Es laufen schon " + running + " Archivsuchen, höchstens "
                + settings.maxJobs() + " gleichzeitig. Bitte warten oder eine abbrechen.");
        }
        ArchiveSearchRequest normalized = new ArchiveSearchRequest(List.copyOf(apps), request.hosts(), from, to,
            query, request.regex(), request.ignoreCase(), context);
        ArchiveSearchJob job = new ArchiveSearchJob(HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong()),
            owner, normalized, List.copyOf(apps), pseudonymized, session, now, now.plus(settings.maxRuntime()),
            settings.maxHits());
        jobs.put(job.id(), job);
        plan(job, normalized, new Matching(raw, check));
        LOGGER.info("archive search {} by {}: {} in {} from {} to {}", job.id(), owner.user(), query, apps, from,
            to);
        return job;
    }

    /**
     * Replaces the pseudonyms in a regular expression. {@code $Nutzer1} and {@code \$Nutzer1} both stand for the
     * pseudonym, an end of line followed by a word could never match anyway.
     *
     * @param query the expression
     * @param replacement the expression for a pseudonym
     * @return the expression with the pseudonyms replaced
     */
    static String pseudonymsInRegex(String query, UnaryOperator<String> replacement) {
        java.util.regex.Matcher matcher = REGEX_PSEUDONYM.matcher(query);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out,
                java.util.regex.Matcher.quoteReplacement(replacement.apply(matcher.group(1))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static LineMatcher matcher(String query, ArchiveSearchRequest request) {
        if (!request.regex()) {
            return new LineMatcher.Literal(query, request.ignoreCase());
        }
        try {
            // the frame is searched as one text, ^ and $ stand for the start and end of each line
            return new LineMatcher.Regex(Pattern.compile(query,
                Pattern.MULTILINE | (request.ignoreCase() ? Pattern.CASE_INSENSITIVE : 0)));
        } catch (PatternSyntaxException e) {
            throw new SearchException("Ungültiger regulärer Ausdruck: " + e.getDescription()
                + ". Rückverweise und Lookaround gibt es in RE2 nicht.");
        }
    }

    /**
     * Finds a job of an owner.
     *
     * @param owner who asks
     * @param id the job
     * @return the job
     * @throws NoSuchElementException if there is no such job for the owner
     */
    public ArchiveSearchJob job(Owner owner, String id) {
        cleanUp();
        ArchiveSearchJob job = jobs.get(id);
        if (job == null || !job.owner().equals(owner)) {
            throw new NoSuchElementException("Die Archivsuche „" + id + "“ gibt es nicht (mehr).");
        }
        return job;
    }

    /**
     * The jobs of an owner, newest first.
     *
     * @param owner who asks
     * @return their jobs
     */
    public List<ArchiveSearchJob> jobs(Owner owner) {
        cleanUp();
        return jobs.values().stream().filter(job -> job.owner().equals(owner))
            .sorted(Comparator.comparing((ArchiveSearchJob job) -> job.status().started()).reversed()).toList();
    }

    /**
     * Cancels a job.
     *
     * @param owner who asks
     * @param id the job
     * @return the job
     * @throws NoSuchElementException if there is no such job for the owner
     */
    public ArchiveSearchJob cancel(Owner owner, String id) {
        ArchiveSearchJob job = job(owner, id);
        if (job.cancel(clock.instant())) {
            LOGGER.info("archive search {} cancelled", id);
        }
        return job;
    }

    private void cleanUp() {
        Instant limit = clock.instant().minus(settings.keep());
        jobs.values().removeIf(job -> {
            Instant finished = job.finished();
            return finished != null && finished.isBefore(limit);
        });
    }

    private void plan(ArchiveSearchJob job, ArchiveSearchRequest request, Matching matching) {
        List<Planned> planned = new ArrayList<>();
        int frames = 0;
        long bytes = 0;
        for (ArchiveFile file : archive.files()) {
            ArchiveMeta meta = file.meta();
            if (!request.apps().contains(meta.app())
                || !request.hosts().isEmpty() && !request.hosts().contains(meta.host())
                || meta.lines() == 0 || !overlaps(meta.from(), meta.to(), request)) {
                continue;
            }
            List<ArchiveFrame> all = file.frames();
            List<Integer> selected = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                ArchiveFrame frame = all.get(i);
                if (frame.rlen() > 0 && overlaps(frame.from(), frame.to(), request)) {
                    selected.add(i);
                    bytes += frame.rlen();
                }
            }
            if (!selected.isEmpty()) {
                frames += selected.size();
                planned.add(new Planned(new Source(file, all), selected, meta.from()));
            }
        }
        planned.sort(Comparator.comparing(Planned::from, Comparator.nullsFirst(Comparator.naturalOrder())));
        job.planned(planned.size(), frames, bytes);
        if (planned.isEmpty()) {
            job.finish(State.DONE, null, clock.instant());
            return;
        }
        AtomicInteger filesLeft = new AtomicInteger(planned.size());
        AtomicInteger framesLeft = new AtomicInteger(frames);
        for (Planned file : planned) {
            AtomicInteger left = new AtomicInteger(file.frames().size());
            for (int index : file.frames()) {
                workers.execute(() -> {
                    try {
                        search(job, request, matching, file.source(), index);
                    } catch (RuntimeException e) {
                        LOGGER.error("archive search {} failed in {}", job.id(), file.source().path(), e);
                        job.addFailure(file.source().path());
                    } finally {
                        if (left.decrementAndGet() == 0) {
                            job.fileDone();
                            filesLeft.decrementAndGet();
                        }
                        if (framesLeft.decrementAndGet() == 0) {
                            job.finish(State.DONE, null, clock.instant());
                            LOGGER.info("archive search {} finished with {} hits", job.id(), job.status().hits());
                        }
                    }
                });
            }
        }
    }

    private static boolean overlaps(Instant from, Instant to, ArchiveSearchRequest request) {
        return (to == null || !to.isBefore(request.from())) && (from == null || from.isBefore(request.to()));
    }

    private void search(ArchiveSearchJob job, ArchiveSearchRequest request, Matching matching, Source source,
        int index) {
        if (!job.running()) {
            return;
        }
        if (clock.instant().isAfter(job.deadline())) {
            job.finish(State.TIMEOUT, "Laufzeit von " + settings.maxRuntime().toMinutes()
                + " Minuten überschritten, der Zeitraum ist nicht vollständig durchsucht", clock.instant());
            return;
        }
        ArchiveFrame frame = source.frames().get(index);
        Map<Integer, Optional<FrameLines>> cache = new HashMap<>();
        Optional<FrameLines> lines = source.lines(index, cache);
        if (lines.isEmpty()) {
            job.addFailure(source.path());
            job.frameDone(frame.rlen());
            return;
        }
        List<ArchiveHit> hits = new ArrayList<>();
        FrameLines text = lines.get();
        LineMatcher.InText next = matching.raw().in(text.text());
        int position = 0;
        int length = text.text().length();
        ArchiveMeta meta = source.file().meta();
        int start;
        while (position < length && (start = next.next(position)) >= 0) {
            int line = text.lineAt(start);
            if (line < 0 || line >= text.count()) {
                break;
            }
            position = text.nextStart(line);
            String content = text.line(line);
            // a match may run over the line end, like grep only the line itself counts
            if (!next.test(content)) {
                continue;
            }
            hit(job, matching, source, index, cache, text, line, request.context(), meta).ifPresent(hits::add);
        }
        job.addHits(hits, clock.instant());
        job.frameDone(frame.rlen());
    }

    private Optional<ArchiveHit> hit(ArchiveSearchJob job, Matching matching, Source source, int index,
        Map<Integer, Optional<FrameLines>> cache, FrameLines text, int line, int context, ArchiveMeta meta) {
        String content = text.line(line);
        List<String> before = new ArrayList<>();
        for (int i = line - context; i < line; i++) {
            if (i >= 0) {
                before.add(text.line(i));
            } else {
                source.lineBefore(index, -i, cache).ifPresent(before::add);
            }
        }
        List<String> after = new ArrayList<>();
        for (int i = line + 1; i <= line + context; i++) {
            if (i < text.count()) {
                after.add(text.line(i));
            } else {
                source.lineAfter(index, i - text.count(), cache).ifPresent(after::add);
            }
        }
        if (job.pseudonymized()) {
            Optional<String> pseudonymized = pseudonyms.text(job.session(), meta.app(), content);
            if (pseudonymized.isEmpty() || !matching.check().test(pseudonymized.get())) {
                return Optional.empty();
            }
            content = pseudonymized.get();
            before = pseudonymize(job, meta.app(), before);
            after = pseudonymize(job, meta.app(), after);
        }
        return Optional.of(new ArchiveHit(meta.app(), meta.host(), source.path(), text.offset(line),
            before.stream().map(this::cut).toList(), cut(content), after.stream().map(this::cut).toList()));
    }

    private List<String> pseudonymize(ArchiveSearchJob job, String app, List<String> lines) {
        return lines.stream()
            .map(line -> pseudonyms.text(job.session(), app, line).orElse(PseudonymizedSearch.HELD_BACK)).toList();
    }

    private String cut(String line) {
        int max = settings.maxLineLength();
        if (line.length() <= max) {
            return line;
        }
        int end = Character.isHighSurrogate(line.charAt(max - 1)) ? max - 1 : max;
        return line.substring(0, end) + " … [gekürzt, " + line.length() + " Zeichen]";
    }

    /**
     * Stops the worker threads.
     */
    @Override
    public void close() {
        jobs.values().forEach(job -> job.cancel(clock.instant()));
        workers.shutdownNow();
    }

    private record Matching(LineMatcher raw, LineMatcher check) {
    }

    private record Planned(Source source, List<Integer> frames, Instant from) {
    }

    /**
     * An archive file during a job. If the file is written again by {@code archive refilter}, the frames are read
     * from the new file, found by the original bytes they were made of.
     */
    private final class Source {

        private final ArchiveFile planned;

        private final List<ArchiveFrame> frames;

        private final String path;

        private volatile ArchiveFile file;

        Source(ArchiveFile file, List<ArchiveFrame> frames) {
            this.planned = file;
            this.file = file;
            this.frames = frames;
            this.path = file.relativePath();
        }

        ArchiveFile file() {
            return file;
        }

        List<ArchiveFrame> frames() {
            return frames;
        }

        String path() {
            return path;
        }

        /**
         * Reads a frame.
         *
         * @param index index of the frame in the planned file
         * @param cache frames already read by this task
         * @return the lines, empty if the frame cannot be read
         */
        Optional<FrameLines> lines(int index, Map<Integer, Optional<FrameLines>> cache) {
            return cache.computeIfAbsent(index, this::read);
        }

        private Optional<FrameLines> read(int index) {
            ArchiveFrame frame = frames.get(index);
            for (int attempt = 0; attempt < 2; attempt++) {
                ArchiveFile current = file;
                Optional<ArchiveFrame> target = current == planned ? Optional.of(frame) : renewed(current, frame);
                if (target.isEmpty()) {
                    break;
                }
                try {
                    return Optional.of(new FrameLines(current.readFrame(target.get()), target.get().raw()));
                } catch (IOException e) {
                    Optional<ArchiveFile> found = archive.find(current.fingerprint());
                    if (found.isEmpty() || found.get() == current) {
                        LOGGER.warn("archive search cannot read {}: {}", path, e.getMessage());
                        break;
                    }
                    file = found.get();
                }
            }
            return Optional.empty();
        }

        private static Optional<ArchiveFrame> renewed(ArchiveFile current, ArchiveFrame frame) {
            return current.frames().stream()
                .filter(candidate -> candidate.src() == frame.src() && candidate.srcEnd() == frame.srcEnd())
                .findFirst();
        }

        // the n-th line before the frame, 1 is its last line
        Optional<String> lineBefore(int index, int n, Map<Integer, Optional<FrameLines>> cache) {
            if (index == 0) {
                return Optional.empty();
            }
            return lines(index - 1, cache).filter(lines -> lines.count() >= n)
                .map(lines -> lines.line(lines.count() - n));
        }

        // the n-th line after the frame, 0 is the first line of the next frame
        Optional<String> lineAfter(int index, int n, Map<Integer, Optional<FrameLines>> cache) {
            if (index + 1 >= frames.size()) {
                return Optional.empty();
            }
            return lines(index + 1, cache).filter(lines -> lines.count() > n).map(lines -> lines.line(n));
        }
    }
}
