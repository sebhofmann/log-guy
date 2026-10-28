package de.gbv.logguy.server.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;

import de.gbv.logguy.server.config.ConfigChecker;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.script.ModuleInfo;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ParserScript;
import de.gbv.logguy.server.script.ScriptException;
import de.gbv.logguy.server.script.ScriptKind;
import de.gbv.logguy.server.script.ScriptRuntime;
import de.gbv.logguy.server.script.ScriptStore;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import de.gbv.logguy.server.security.ControlToken;
import de.gbv.logguy.server.security.SecurityConfig;
import de.gbv.logguy.server.security.TokenRegistry;
import de.gbv.logguy.server.solr.SolrService;

/**
 * Commands of {@code log-guy <command>}. They run in the profile {@code cli} without web server.
 */
public class Commands {

    private final LogGuyProperties properties;

    private final ParserRegistry parsers;

    private final SolrService solr;

    private final ScriptRuntime runtime;

    private final CliExitCode exitCode;

    private final ScriptStore store;

    private final CliProperties cli;

    /**
     * Creates the commands.
     *
     * @param properties configuration
     * @param parsers the parser scripts
     * @param solr connection to Solr, only used by Solr commands
     * @param runtime the script runtime
     * @param exitCode exit code of the process
     * @param store the script directory
     * @param cli settings of the command line
     */
    public Commands(LogGuyProperties properties, ParserRegistry parsers, SolrService solr, ScriptRuntime runtime,
        CliExitCode exitCode, ScriptStore store, CliProperties cli) {
        this.properties = properties;
        this.parsers = parsers;
        this.solr = solr;
        this.runtime = runtime;
        this.exitCode = exitCode;
        this.store = store;
        this.cli = cli;
    }

    /**
     * Checks configuration and scripts without changing anything.
     *
     * @param context the command context
     */
    @Command(name = "check", description = "Check application.yml and all scripts")
    public void check(CommandContext context) {
        PrintWriter out = context.outputWriter();
        List<String> errors = new ArrayList<>(ConfigChecker.check(properties));
        errors.addAll(parsers.load());
        for (Map.Entry<ScriptId, String> script : store.active().entrySet()) {
            if (script.getKey().kind() != ScriptKind.PARSER) {
                try {
                    ModuleInfo.check(runtime, script.getKey().kind(), script.getKey().name(), script.getValue());
                } catch (ScriptException e) {
                    errors.add(e.getMessage());
                }
            }
        }
        if (errors.isEmpty()) {
            out.println("configuration and scripts are fine: " + properties.apps().size() + " applications, "
                + properties.tokens().size() + " tokens");
        } else {
            errors.forEach(error -> out.println("ERROR " + error));
            exitCode.fail();
        }
        out.flush();
    }

    /**
     * Lets the running server read configuration and scripts again, authenticated by {@code state/control.token}.
     *
     * @param context the command context
     * @param script only this script, e.g. {@code parser/digibib}
     */
    @Command(name = "reload", description = "Reload configuration and scripts of the running server")
    public void reload(CommandContext context,
        @Argument(index = 0, description = "only this script, e.g. parser/digibib", defaultValue = "") String script) {
        String query =
            script == null || script.isBlank() ? "" : "?script=" + URLEncoder.encode(script, StandardCharsets.UTF_8);
        control(context, SecurityConfig.RELOAD_PATH + query);
    }

    /**
     * Lets the running server write archive files again from {@code hot/} with the current lifecycle filters.
     *
     * @param context the command context
     * @param app only files of this application
     * @param all all files with an original, not only those with placeholders
     */
    @Command(name = { "archive", "refilter" },
        description = "Write archive files with placeholders again from hot/ with the current lifecycle filters")
    public void archiveRefilter(CommandContext context,
        @Option(longName = "app", description = "only this application", defaultValue = "") String app,
        @Option(longName = "all", description = "all files still in hot/, not only those with placeholders",
            defaultValue = "false") boolean all) {
        String query = "?all=" + all + (app == null || app.isBlank() ? "" : "&app=" + URLEncoder.encode(app,
            StandardCharsets.UTF_8));
        control(context, SecurityConfig.REFILTER_PATH + query);
    }

    /**
     * Lets the running server pseudonymize archive files again with the active pseudonymization scripts.
     *
     * @param context the command context
     * @param app only files of this application
     * @param all also files already pseudonymized with the current scripts
     */
    @Command(name = { "archive", "pseudonymize" },
        description = "Pseudonymize archive files again with the active pseudonymization scripts")
    public void archivePseudonymize(CommandContext context,
        @Option(longName = "app", description = "only this application", defaultValue = "") String app,
        @Option(longName = "all", description = "also files already pseudonymized with the current scripts",
            defaultValue = "false") boolean all) {
        String query = "?all=" + all + (app == null || app.isBlank() ? "" : "&app=" + URLEncoder.encode(app,
            StandardCharsets.UTF_8));
        control(context, SecurityConfig.PSEUDONYMIZE_PATH + query);
    }

    /**
     * Lets the running server index a time range of an application again: files of {@code hot/} with the current
     * parser, the part before the index window from the archive with an expiry date.
     *
     * @param context the command context
     * @param app the application
     * @param since start of the range, a duration back from now like {@code 14d} or a time like
     *     {@code 2026-10-01T00:00:00Z}
     * @param until end of the range in the same form, now if empty
     * @param source {@code all}, {@code hot} or {@code archive}
     */
    @Command(name = "reindex", description = "Index a time range of an application again, older days from the archive")
    public void reindex(CommandContext context,
        @Option(longName = "app", description = "the application", required = true) String app,
        @Option(longName = "since", description = "start, e.g. 14d, 36h or 2026-10-01T00:00:00Z",
            required = true) String since,
        @Option(longName = "until", description = "end in the same form, default now", defaultValue = "") String until,
        @Option(longName = "source", description = "all, hot or archive", defaultValue = "all") String source) {
        Instant now = Instant.now();
        Instant from;
        Instant to;
        try {
            from = pointInTime(since, now);
            to = until == null || until.isBlank() ? null : pointInTime(until, now);
        } catch (IllegalArgumentException e) {
            context.outputWriter().println("ERROR " + e.getMessage());
            context.outputWriter().flush();
            exitCode.fail();
            return;
        }
        StringBuilder query = new StringBuilder("?app=").append(URLEncoder.encode(app, StandardCharsets.UTF_8))
            .append("&from=").append(from).append("&source=").append(source.toUpperCase(Locale.ROOT));
        if (to != null) {
            query.append("&to=").append(to);
        }
        control(context, SecurityConfig.REINDEX_PATH + query);
    }

    /**
     * Reads a point in time: a duration back from now with the unit {@code d}, {@code h} or {@code m}, or an ISO
     * time.
     *
     * @param text the text
     * @param now the current time
     * @return the point in time
     * @throws IllegalArgumentException if the text is neither
     */
    static Instant pointInTime(String text, Instant now) {
        String value = text.strip();
        if (value.matches("\\d+[dhm]")) {
            long amount = Long.parseLong(value.substring(0, value.length() - 1));
            Duration back = switch (value.charAt(value.length() - 1)) {
                case 'd' -> Duration.ofDays(amount);
                case 'h' -> Duration.ofHours(amount);
                default -> Duration.ofMinutes(amount);
            };
            return now.minus(back);
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("neither a duration like 14d nor a time like 2026-10-01T00:00:00Z: "
                + text, e);
        }
    }

    /**
     * Sends a request with the control token to the running server and prints the answer.
     */
    private void control(CommandContext context, String pathAndQuery) {
        PrintWriter out = context.outputWriter();
        try {
            String token = ControlToken.read(properties.dataDir());
            URI uri = URI.create(cli.serverUrl().toString().replaceAll("/+$", "") + pathAndQuery);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<String> response;
            try (HttpClient client = HttpClient.newHttpClient()) {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            }
            out.println(response.body());
            if (response.statusCode() != 200) {
                exitCode.fail();
            }
        } catch (IOException e) {
            out.println("ERROR " + e.getMessage());
            exitCode.fail();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitCode.fail();
        }
        out.flush();
    }

    /**
     * Uploads the configset, creates the collection and sets the alias.
     *
     * @param context the command context
     */
    @Command(name = { "solr", "init" }, description = "Upload configset, create collection and alias in SolrCloud")
    public void solrInit(CommandContext context) {
        PrintWriter out = context.outputWriter();
        try {
            solr.init().forEach(out::println);
        } catch (Exception e) {
            out.println("ERROR " + e.getMessage());
            exitCode.fail();
        }
        out.flush();
    }

    /**
     * Measures a parser script on a log file.
     *
     * @param context the command context
     * @param script name of a parser in the script directory or the JAR, or path of a {@code .js} file
     * @param file the log file, plain or {@code .gz}
     * @param rounds number of rounds
     */
    @Command(name = { "bench", "parser" }, description = "Measure a parser script on a log file")
    public void benchParser(CommandContext context,
        @Option(longName = "script", description = "parser name or .js file", required = true) String script,
        @Option(longName = "file", description = "log file, plain or .gz", required = true) String file,
        @Option(longName = "rounds", description = "number of rounds", defaultValue = "3") int rounds) {
        PrintWriter out = context.outputWriter();
        try {
            Path path = Path.of(script);
            String code = script.endsWith(".js") ? Files.readString(path) : parsers.source(script);
            String name = script.endsWith(".js") ? path.getFileName().toString().replace(".js", "") : script;
            ParserBenchmark.run(runtime, ParserScript.load(runtime, name, code), Path.of(file), rounds, out);
        } catch (Exception e) {
            out.println("ERROR " + e.getMessage());
            exitCode.fail();
        }
        out.flush();
    }

    /**
     * Creates a token, shows it once and prints the entry for {@code application.yml} with its hash.
     *
     * @param context the command context
     * @param kind ingest, api or mcp
     * @param name name of the token
     * @param host host of an ingest token
     * @param apps applications of an ingest token, separated by commas
     * @param user user of an API or MCP token
     */
    @Command(name = { "token", "create" }, description = "Create a token and print its configuration entry")
    public void tokenCreate(CommandContext context,
        @Option(longName = "kind", description = "ingest, api or mcp", required = true) String kind,
        @Option(longName = "name", description = "name of the token", required = true) String name,
        @Option(longName = "host", description = "host of an ingest token") String host,
        @Option(longName = "apps", description = "applications of an ingest token, comma separated") String apps,
        @Option(longName = "user", description = "user of an api or mcp token") String user) {
        PrintWriter out = context.outputWriter();
        TokenKind tokenKind = TokenKind.valueOf(kind.toUpperCase(Locale.ROOT));
        String token = TokenRegistry.generate(tokenKind);
        out.println("Token, shown only now: " + token);
        out.println();
        out.println("Add to log-guy.tokens in /etc/log-guy/application.yml and run log-guy reload:");
        out.println("    - name: " + name);
        out.println("      kind: " + tokenKind.name().toLowerCase(Locale.ROOT));
        if (tokenKind == TokenKind.INGEST) {
            out.println("      host: " + host);
            out.println("      apps: " + (apps == null ? "[]" : Arrays.asList(apps.split(","))));
        } else {
            out.println("      user: " + user);
        }
        out.println("      hash: \"" + TokenRegistry.hash(token) + "\"");
        out.flush();
    }

    /**
     * Hashes a password for {@code password-hash} of a user. The password is read from the terminal without echo, or
     * as the first line of standard input.
     *
     * @param context the command context
     * @param name name of the user, only for the printed entry
     */
    @Command(name = { "password", "hash" }, description = "Hash a password read from the terminal or stdin")
    public void passwordHash(CommandContext context,
        @Option(longName = "name", description = "name of the user", required = true) String name) {
        PrintWriter out = context.outputWriter();
        try {
            String password;
            Console console = System.console();
            if (console != null && console.isTerminal()) {
                char[] chars = console.readPassword("Password: ");
                password = chars == null ? "" : new String(chars);
            } else {
                String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                password = line == null ? "" : line;
            }
            if (password.isEmpty()) {
                out.println("ERROR empty password");
                exitCode.fail();
            } else {
                out.println("Add to log-guy.users in /etc/log-guy/application.yml and run log-guy reload:");
                out.println("    - name: " + name);
                out.println("      password-hash: \"" + SecurityConfig.passwordEncoder().encode(password) + "\"");
                out.println("      grants:");
                out.println("        - apps: [\"*\"]");
                out.println("          rights: [read]");
            }
        } catch (IOException e) {
            out.println("ERROR " + e.getMessage());
            exitCode.fail();
        }
        out.flush();
    }
}
