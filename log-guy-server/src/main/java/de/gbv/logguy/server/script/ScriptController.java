package de.gbv.logguy.server.script;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.gbv.logguy.server.audit.Actor;
import de.gbv.logguy.server.audit.Actor.Via;
import de.gbv.logguy.server.script.ScriptRequestException.Reason;
import de.gbv.logguy.server.script.ScriptService.Caller;
import de.gbv.logguy.server.script.ScriptService.ScriptDetail;
import de.gbv.logguy.server.script.ScriptService.ScriptSummary;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.script.ScriptTester.TestSource;
import de.gbv.logguy.server.security.CurrentUser;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * The script API: list, read, drafts, tests, activation, rollback and removal.
 */
@RestController
@RequestMapping("/api/v1/scripts")
@Profile("!cli")
public class ScriptController {

    private final ScriptService scripts;

    private final ScriptTester tester;

    private final CurrentUser currentUser;

    /**
     * Creates the controller.
     *
     * @param scripts the scripts
     * @param tester runs tests
     * @param currentUser resolves the user
     */
    public ScriptController(ScriptService scripts, ScriptTester tester, CurrentUser currentUser) {
        this.scripts = scripts;
        this.tester = tester;
        this.currentUser = currentUser;
    }

    /**
     * Lists the scripts the user may see.
     *
     * @return the scripts
     * @throws IOException if the script directory cannot be read
     */
    @GetMapping
    public List<ScriptSummary> list() throws IOException {
        return scripts.list(currentUser.get());
    }

    /**
     * Reads a script with draft and history.
     *
     * @param kind the kind
     * @param name the name
     * @return the script
     * @throws IOException if the script directory cannot be read
     */
    @GetMapping("/{kind}/{name}")
    public ScriptDetail get(@PathVariable String kind, @PathVariable String name) throws IOException {
        return scripts.get(currentUser.get(), id(kind, name));
    }

    /**
     * Saves a draft.
     *
     * @param kind the kind
     * @param name the name
     * @param body {@code code} and {@code base}, the hash of the active version the draft starts from
     * @return the script
     * @throws IOException if the draft cannot be written
     */
    @PutMapping("/{kind}/{name}/draft")
    public ScriptDetail saveDraft(@PathVariable String kind, @PathVariable String name,
        @RequestBody DraftRequest body) throws IOException {
        if (body == null || body.code() == null) {
            throw new ScriptRequestException(Reason.INVALID, "Der Quelltext fehlt.");
        }
        return scripts.saveDraft(caller(), id(kind, name), body.code(), body.base());
    }

    /**
     * Discards a draft.
     *
     * @param kind the kind
     * @param name the name
     * @return 204
     * @throws IOException if the draft cannot be deleted
     */
    @DeleteMapping("/{kind}/{name}/draft")
    public ResponseEntity<Void> discardDraft(@PathVariable String kind, @PathVariable String name)
        throws IOException {
        scripts.discardDraft(caller(), id(kind, name));
        return ResponseEntity.noContent().build();
    }

    /**
     * Runs the draft, the active version or the sent code against a test source.
     *
     * @param kind the kind
     * @param name the name
     * @param body the code and the test source
     * @return the result
     * @throws IOException if the source cannot be read
     */
    @PostMapping("/{kind}/{name}/test")
    public Object test(@PathVariable String kind, @PathVariable String name, @RequestBody TestRequest body)
        throws IOException {
        if (body == null) {
            throw new ScriptRequestException(Reason.INVALID, "Die Testquelle fehlt.");
        }
        ScriptId id = id(kind, name);
        TestSource source = new TestSource(body.app(), body.lines(), body.from(), body.to(), body.file(),
            body.text());
        return tester.test(currentUser.get(), id, body.code(), Boolean.TRUE.equals(body.active()), source);
    }

    /**
     * Activates the draft.
     *
     * @param kind the kind
     * @param name the name
     * @param force activate although the active version changed since the draft was started
     * @param confirm activate although the last test had more than 1 % errors
     * @return the script
     * @throws IOException if the script cannot be written
     */
    @PostMapping("/{kind}/{name}/activate")
    public ScriptDetail activate(@PathVariable String kind, @PathVariable String name,
        @RequestParam(defaultValue = "false") boolean force, @RequestParam(defaultValue = "false") boolean confirm)
        throws IOException {
        return scripts.activate(caller(), id(kind, name), force, confirm);
    }

    /**
     * Activates an earlier version.
     *
     * @param kind the kind
     * @param name the name
     * @param at when the version was activated, as listed in the history
     * @return the script
     * @throws IOException if the script cannot be written
     */
    @PostMapping("/{kind}/{name}/rollback")
    public ScriptDetail rollback(@PathVariable String kind, @PathVariable String name, @RequestParam String at)
        throws IOException {
        Instant instant;
        try {
            instant = Instant.parse(at);
        } catch (DateTimeParseException e) {
            throw new ScriptRequestException(Reason.INVALID, "Ungültiger Zeitpunkt „" + at + "“.");
        }
        return scripts.rollback(caller(), id(kind, name), instant);
    }

    /**
     * Removes a script, except parsers.
     *
     * @param kind the kind
     * @param name the name
     * @return 204
     * @throws IOException if the script cannot be deleted
     */
    @DeleteMapping("/{kind}/{name}")
    public ResponseEntity<Void> delete(@PathVariable String kind, @PathVariable String name) throws IOException {
        scripts.delete(caller(), id(kind, name));
        return ResponseEntity.noContent().build();
    }

    private Caller caller() {
        LogGuyUser user = currentUser.get();
        Actor actor = user.token() == null ? new Actor(user.name(), null, Via.GUI)
            : new Actor(user.name(), user.token(), Via.API);
        return new Caller(user, actor, true);
    }

    /**
     * Parses the kind and name of a script from a request.
     *
     * @param kind the directory of the kind, e.g. {@code parser}
     * @param name the name
     * @return the id
     * @throws ScriptRequestException if the kind is unknown or the name invalid
     */
    public static ScriptId id(String kind, String name) {
        ScriptKind parsed;
        try {
            parsed = ScriptKind.of(kind);
        } catch (IllegalArgumentException e) {
            throw new ScriptRequestException(Reason.NOT_FOUND, "Unbekannte Skriptart „" + kind + "“.");
        }
        try {
            return new ScriptId(parsed, name);
        } catch (IllegalArgumentException e) {
            throw new ScriptRequestException(Reason.INVALID, "Ungültiger Skriptname „" + name + "“.");
        }
    }

    /**
     * Body of a draft.
     *
     * @param code the code
     * @param base hash of the active version the draft starts from, {@code null} for a new script
     */
    public record DraftRequest(String code, String base) {
    }

    /**
     * Body of a test. Exactly one source is set: {@code app}, {@code file} or {@code text}.
     *
     * @param code code to test instead of the draft, may be {@code null}
     * @param active test the active version even if there is a draft
     * @param app an application
     * @param lines the most lines
     * @param from start of a time range for {@code app}
     * @param to end of the time range
     * @param file a file in {@code hot/}
     * @param text pasted lines
     */
    public record TestRequest(String code, Boolean active, String app, Integer lines, Instant from, Instant to,
        String file, String text) {
    }
}
