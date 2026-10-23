package de.gbv.logguy.server.cli;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;

import de.gbv.logguy.server.config.ConfigChecker;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ParserScript;
import de.gbv.logguy.server.script.ScriptRuntime;
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

    /**
     * Creates the commands.
     *
     * @param properties configuration
     * @param parsers the parser scripts
     * @param solr connection to Solr, only used by Solr commands
     * @param runtime the script runtime
     * @param exitCode exit code of the process
     */
    public Commands(LogGuyProperties properties, ParserRegistry parsers, SolrService solr, ScriptRuntime runtime,
        CliExitCode exitCode) {
        this.properties = properties;
        this.parsers = parsers;
        this.solr = solr;
        this.runtime = runtime;
        this.exitCode = exitCode;
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
}
