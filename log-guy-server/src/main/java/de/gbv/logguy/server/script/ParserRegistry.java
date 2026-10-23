package de.gbv.logguy.server.script;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;
import de.gbv.logguy.server.script.ScriptStore.ScriptId;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parser scripts of all applications.
 * <p>
 * An application uses the script {@code parser/<parser>.js} from the script directory, where {@code parser} is set in
 * the application config and defaults to the application name. If the script directory has no such file, the template
 * of the same name from the JAR is used, e.g. {@code mycore-log4j}.
 */
public class ParserRegistry {

    private static final Logger LOGGER = LogManager.getLogger();

    private final LogGuyProperties properties;

    private final ScriptRuntime runtime;

    private final ScriptStore store;

    private final AtomicReference<Map<String, ParserScript>> byApp = new AtomicReference<>(Map.of());

    private volatile boolean loaded;

    /**
     * Creates the registry, scripts are loaded by {@link #load()}.
     *
     * @param properties configuration
     * @param runtime the script runtime
     * @param store the script directory with the active scripts
     */
    public ParserRegistry(LogGuyProperties properties, ScriptRuntime runtime, ScriptStore store) {
        this.properties = properties;
        this.runtime = runtime;
        this.store = store;
    }

    /**
     * Creates the registry with its own store that reads the script directory once, for tests and tools.
     *
     * @param properties configuration
     * @param runtime the script runtime
     */
    public ParserRegistry(LogGuyProperties properties, ScriptRuntime runtime) {
        this(properties, runtime, scannedStore(properties));
    }

    private static ScriptStore scannedStore(LogGuyProperties properties) {
        ScriptStore store = new ScriptStore(properties.scripts().dir(), Clock.systemUTC(),
            JsonMapper.builder().build());
        try {
            store.scan();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return store;
    }

    /**
     * Loads the parser of every configured application from the active scripts. Nothing is changed if a script has
     * errors.
     *
     * @return the errors, empty if everything was loaded
     */
    public List<String> load() {
        Prepared prepared = prepare(store.active());
        if (prepared.errors().isEmpty()) {
            apply(prepared);
        }
        return prepared.errors();
    }

    /**
     * Checks the parsers of all applications with the given scripts, without activating anything.
     *
     * @param scripts the scripts to use, parsers missing here come from the templates
     * @return the checked parsers and the errors
     */
    public Prepared prepare(Map<ScriptId, String> scripts) {
        Map<String, ParserScript> result = new HashMap<>();
        Map<String, ParserScript> byName = new HashMap<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, AppProperties> app : properties.apps().entrySet()) {
            String name = parserName(app.getKey(), app.getValue());
            try {
                ParserScript script = byName.get(name);
                if (script == null) {
                    String code = scripts.get(new ScriptId(ScriptKind.PARSER, name));
                    script = ParserScript.load(runtime, name, code != null ? code : template(name));
                    byName.put(name, script);
                }
                result.put(app.getKey(), script);
            } catch (ScriptException | IOException e) {
                errors.add("app " + app.getKey() + ": " + e.getMessage());
            }
        }
        return new Prepared(Map.copyOf(result), List.copyOf(errors));
    }

    /**
     * Activates parsers checked by {@link #prepare(Map)}. Indexer threads take them before their next batch.
     *
     * @param prepared the checked parsers, must have no errors
     */
    public void apply(Prepared prepared) {
        if (!prepared.errors().isEmpty()) {
            throw new IllegalArgumentException("parsers have errors");
        }
        byApp.set(prepared.byApp());
        loaded = true;
        LOGGER.info("loaded parsers for {} applications", prepared.byApp().size());
    }

    /**
     * Loads the parsers if no {@link #load()} has succeeded yet, e.g. in a process without indexer.
     *
     * @return the errors of the load, empty if loaded now or before
     */
    public synchronized List<String> ensureLoaded() {
        if (loaded) {
            return List.of();
        }
        List<String> errors = load();
        errors.forEach(error -> LOGGER.error("cannot load parser: {}", error));
        return errors;
    }

    /**
     * The parser script of an application.
     *
     * @param app the application
     * @return the script, empty if the application is unknown
     */
    public Optional<ParserScript> forApp(String app) {
        return Optional.ofNullable(byApp.get().get(app));
    }

    /**
     * The name of the parser script of an application.
     *
     * @param app the application
     * @return the name, the application name if none is configured
     */
    public String parserName(String app) {
        return parserName(app, properties.apps().get(app));
    }

    /**
     * The applications that use a parser script.
     *
     * @param name name of the parser
     * @return the applications, sorted
     */
    public Set<String> appsUsing(String name) {
        Set<String> apps = new TreeSet<>();
        for (String app : properties.apps().keySet()) {
            if (parserName(app).equals(name)) {
                apps.add(app);
            }
        }
        return apps;
    }

    private static String parserName(String app, AppProperties properties) {
        return properties == null || properties.parser() == null ? app : properties.parser();
    }

    /**
     * The active code of a parser, from the script directory or the template of the same name from the JAR.
     *
     * @param name name of the parser
     * @return the code
     * @throws IOException if neither exists
     */
    public String source(String name) throws IOException {
        Optional<String> code = store.active(new ScriptId(ScriptKind.PARSER, name));
        return code.isPresent() ? code.get() : template(name);
    }

    /**
     * Whether a template of this name is in the JAR.
     *
     * @param name name of the template
     * @return true if it exists
     */
    public static boolean hasTemplate(String name) {
        return Templates.has(new ScriptId(ScriptKind.PARSER, name));
    }

    /**
     * Reads a template from the JAR.
     *
     * @param name name of the template, e.g. {@code mycore-log4j}
     * @return the code
     * @throws IOException if the template does not exist
     */
    public static String template(String name) throws IOException {
        return Templates.read(new ScriptId(ScriptKind.PARSER, name));
    }

    /**
     * Parsers checked by {@link #prepare(Map)}.
     *
     * @param byApp the parser of every application that could be loaded
     * @param errors the errors, empty if all could be loaded
     */
    public record Prepared(Map<String, ParserScript> byApp, List<String> errors) {
    }
}
