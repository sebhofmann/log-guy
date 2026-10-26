package de.gbv.logguy.server.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.Actor.Via;
import de.gbv.logguy.server.audit.AuditLog;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.files.FileService.FileInfo;
import de.gbv.logguy.server.files.FileService.Listing;
import de.gbv.logguy.server.files.FileService.Page;
import de.gbv.logguy.server.files.FileService.SearchResult;
import de.gbv.logguy.server.files.FileService.Source;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.reindex.ReindexException;
import de.gbv.logguy.server.reindex.ReindexJob;
import de.gbv.logguy.server.reindex.ReindexJob.Plan;
import de.gbv.logguy.server.reindex.ReindexService;
import de.gbv.logguy.server.search.SearchException;
import de.gbv.logguy.server.security.CurrentUser;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * The API of the log browser. Listing files and reading the archived form needs the right {@code archive} and read
 * access to the application, pseudonymized readers see pseudonymized lines. The original in {@code hot/} is
 * unfiltered and only for {@code admin}, a download needs plain read access. Deleting needs {@code delete}.
 */
@RestController
@RequestMapping("/api/v1/files")
@Profile("!cli")
public class FileController {

    private static final String SESSION_PSEUDONYMS = Pseudonyms.class.getName();

    private static final String WITHHELD = "[zurückgehalten]";

    private final FileService files;

    private final ReindexService reindex;

    private final PseudonymService pseudonyms;

    private final CurrentUser currentUser;

    private final AuditLog audit;

    private final HttpServletRequest request;

    /**
     * Creates the controller.
     *
     * @param files the log browser
     * @param reindex indexes a file again
     * @param pseudonyms pseudonymizes lines for pseudonymized readers
     * @param currentUser resolves the user
     * @param audit the audit log
     * @param request the current request, for the pseudonyms of the session
     */
    public FileController(FileService files, ReindexService reindex, PseudonymService pseudonyms,
        CurrentUser currentUser, AuditLog audit, HttpServletRequest request) {
        this.files = files;
        this.reindex = reindex;
        this.pseudonyms = pseudonyms;
        this.currentUser = currentUser;
        this.audit = audit;
        this.request = request;
    }

    /**
     * Lists files, newest first.
     *
     * @param app only this application
     * @param host only this host
     * @param from only files created on or after this day
     * @param to only files created on or before this day
     * @return {@code files}, {@code truncated} and the {@code apps} and {@code hosts} of the visible files
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String app,
        @RequestParam(required = false) String host, @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to) {
        LogGuyUser user = currentUser.get();
        FileService.Facets facets = files.facets(a -> visible(user, a));
        Listing listing = files.list(a -> visible(user, a), blankToNull(app), blankToNull(host), from, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", listing.files());
        result.put("truncated", listing.truncated());
        result.put("apps", facets.apps());
        result.put("hosts", facets.hosts());
        result.put("mayDelete", user.hasRight(Right.DELETE));
        return result;
    }

    /**
     * Details of a file and what the user may do with it.
     *
     * @param fingerprint the file
     * @return the file with {@code may}
     */
    @GetMapping("/{fingerprint}")
    public Map<String, Object> info(@PathVariable String fingerprint) {
        LogGuyUser user = currentUser.get();
        FileInfo info = files.info(fingerprint);
        checkVisible(user, info.app());
        Map<String, Object> may = new LinkedHashMap<>();
        may.put("hot", user.may(Right.ADMIN, info.app()));
        may.put("download", user.readsPlain(info.app()));
        may.put("delete", user.may(Right.DELETE, info.app()));
        may.put("reindexHot", user.may(Right.SCRIPTS, info.app()));
        may.put("reindexArchive", user.may(Right.ARCHIVE, info.app()));
        may.put("pseudonymized", !user.readsPlain(info.app()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", info);
        result.put("may", may);
        return result;
    }

    /**
     * A page of lines.
     *
     * @param fingerprint the file
     * @param source {@code archive} or {@code hot}
     * @param at offset to read forward from
     * @param before offset to read backward from
     * @return the page
     * @throws IOException if reading fails
     */
    @GetMapping("/{fingerprint}/content")
    public Page content(@PathVariable String fingerprint, @RequestParam(defaultValue = "ARCHIVE") Source source,
        @RequestParam(required = false) Long at, @RequestParam(required = false) Long before) throws IOException {
        LogGuyUser user = currentUser.get();
        String app = checkRead(user, fingerprint, source);
        Page page = files.content(fingerprint, source, at, before, lines(user, app));
        if (user.readsPlain(app)) {
            return page;
        }
        return new Page(page.length(), page.lines().stream()
            .map(line -> line.text() == null ? new FileService.Line(line.offset(), WITHHELD) : line).toList(),
            page.prev(), page.next());
    }

    /**
     * Searches lines from an offset on.
     *
     * @param fingerprint the file
     * @param source {@code archive} or {@code hot}
     * @param q the text or regular expression
     * @param regex whether {@code q} is a regular expression
     * @param ignoreCase whether case is ignored
     * @param from offset to start at
     * @return the hits and where to continue
     * @throws IOException if reading fails
     */
    @GetMapping("/{fingerprint}/search")
    public SearchResult search(@PathVariable String fingerprint,
        @RequestParam(defaultValue = "ARCHIVE") Source source, @RequestParam String q,
        @RequestParam(defaultValue = "false") boolean regex, @RequestParam(defaultValue = "false") boolean ignoreCase,
        @RequestParam(defaultValue = "0") long from) throws IOException {
        LogGuyUser user = currentUser.get();
        String app = checkRead(user, fingerprint, source);
        try {
            return files.search(fingerprint, source, q, regex, ignoreCase, from, lines(user, app));
        } catch (IllegalArgumentException e) {
            throw new SearchException(e.getMessage());
        }
    }

    /**
     * The whole file, only for users who read the application in plain text.
     *
     * @param fingerprint the file
     * @param source {@code archive} or {@code hot}
     * @return the file as text
     */
    @GetMapping("/{fingerprint}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String fingerprint,
        @RequestParam(defaultValue = "ARCHIVE") Source source) {
        LogGuyUser user = currentUser.get();
        String app = checkRead(user, fingerprint, source);
        if (!user.readsPlain(app)) {
            throw new AccessDeniedException("plain " + app);
        }
        String name = files.fileName(fingerprint, source);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fingerprint", fingerprint);
        details.put("source", source.name().toLowerCase());
        audit.log(actor(user), "file.download", app, details);
        StreamingResponseBody body = out -> files.download(fingerprint, source, out);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build()
                .toString())
            .header("Cache-Control", "no-store").body(body);
    }

    /**
     * Deletes a file from {@code hot/}, the archive and the index.
     *
     * @param fingerprint the file
     * @param block whether the agents may not send the file again, default true
     * @return what was done
     * @throws IOException if a file cannot be deleted
     * @throws InterruptedException if waiting for the writer or the indexer is interrupted
     */
    @DeleteMapping("/{fingerprint}")
    public FileService.DeleteResult delete(@PathVariable String fingerprint,
        @RequestParam(defaultValue = "true") boolean block) throws IOException, InterruptedException {
        LogGuyUser user = currentUser.get();
        String app = files.app(fingerprint);
        if (!user.may(Right.DELETE, app)) {
            throw new AccessDeniedException("delete " + app);
        }
        FileInfo info = files.info(fingerprint);
        FileService.DeleteResult result = files.delete(fingerprint, block, user.name());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fingerprint", fingerprint);
        details.put("host", info.host());
        details.put("created", String.valueOf(info.created()));
        details.put("paths", info.paths());
        details.put("blocked", block);
        if (result.indexError() != null) {
            details.put("indexError", result.indexError());
        }
        audit.log(actor(user), "file.delete", app, details);
        return result;
    }

    /**
     * Indexes a file again: the part in the index window from {@code hot/}, the older part from the archive.
     *
     * @param fingerprint the file
     * @return the status of the new job
     * @throws ReindexException if there is nothing to do
     */
    @PostMapping("/{fingerprint}/reindex")
    public ResponseEntity<ReindexJob.Status> reindex(@PathVariable String fingerprint) throws ReindexException {
        LogGuyUser user = currentUser.get();
        String app = files.app(fingerprint);
        checkVisible(user, app);
        Plan plan = reindex.planFile(fingerprint);
        if (plan.indexesHot() && !user.may(Right.SCRIPTS, app)) {
            throw new AccessDeniedException("scripts " + app);
        }
        if (plan.loadsArchive() && !user.may(Right.ARCHIVE, app)) {
            throw new AccessDeniedException("archive " + app);
        }
        ReindexJob job = reindex.start(user.name(), plan);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fingerprint", fingerprint);
        details.put("hot", plan.indexesHot());
        details.put("archiveFrom", String.valueOf(plan.archiveFrom()));
        details.put("archiveTo", String.valueOf(plan.archiveTo()));
        audit.log(actor(user), "reindex.start", app, details);
        return ResponseEntity.status(HttpStatus.CREATED).body(job.status());
    }

    /**
     * The blocked files of the applications the user may delete in.
     *
     * @return the entries, last deleted first
     */
    @GetMapping("/blocked")
    public List<DeletedFiles.Entry> blocked() {
        LogGuyUser user = currentUser.get();
        return files.blocked(app -> user.may(Right.DELETE, app));
    }

    /**
     * Lifts the block of a file, the agent may send it again.
     *
     * @param fingerprint the file
     * @return the removed entry
     * @throws IOException if the list cannot be written
     */
    @DeleteMapping("/blocked/{fingerprint}")
    public DeletedFiles.Entry unblock(@PathVariable String fingerprint) throws IOException {
        LogGuyUser user = currentUser.get();
        DeletedFiles.Entry entry = files.blockedEntry(fingerprint);
        if (!user.may(Right.DELETE, entry.app())) {
            throw new AccessDeniedException("delete " + entry.app());
        }
        files.unblock(fingerprint);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fingerprint", fingerprint);
        details.put("host", entry.host());
        audit.log(actor(user), "file.unblock", entry.app(), details);
        return entry;
    }

    private static boolean visible(LogGuyUser user, String app) {
        return user.may(Right.ARCHIVE, app) && user.mayRead(app);
    }

    private static void checkVisible(LogGuyUser user, String app) {
        if (!visible(user, app)) {
            throw new AccessDeniedException("archive " + app);
        }
    }

    private String checkRead(LogGuyUser user, String fingerprint, Source source) {
        String app = files.app(fingerprint);
        checkVisible(user, app);
        if (source == Source.HOT && !user.may(Right.ADMIN, app)) {
            throw new AccessDeniedException("admin " + app);
        }
        return app;
    }

    // plain lines for plain readers, otherwise pseudonymized without colors, null if held back
    private UnaryOperator<String> lines(LogGuyUser user, String app) {
        if (user.readsPlain(app)) {
            return UnaryOperator.identity();
        }
        Pseudonyms session = session(user);
        return line -> pseudonyms.text(session, app, FileService.withoutColors(line)).orElse(null);
    }

    private static Actor actor(LogGuyUser user) {
        return new Actor(user.name(), user.token(), user.token() == null ? Via.GUI : Via.API);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // a GUI session keeps its pseudonyms, a request with an API token gets new ones
    private Pseudonyms session(LogGuyUser user) {
        if (user.token() != null) {
            return new Pseudonyms();
        }
        HttpSession session = request.getSession(true);
        synchronized (session) {
            if (!(session.getAttribute(SESSION_PSEUDONYMS) instanceof Pseudonyms pseudonyms)) {
                Pseudonyms created = new Pseudonyms();
                session.setAttribute(SESSION_PSEUDONYMS, created);
                return created;
            }
            return pseudonyms;
        }
    }
}
