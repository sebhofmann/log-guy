package de.gbv.logguy.server.script;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.AppProperties;

/**
 * The parser scripts of all applications.
 * <p>
 * An application uses the script {@code parser/<parser>.js} from the script directory, where {@code parser} is set in
 * the application config and defaults to the application name. If the script directory has no such file, the template
 * of the same name from the JAR is used, e.g. {@code mycore-log4j}.
 */
public class ParserRegistry {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String TEMPLATES = "/templates/parser/";

    private final LogGuyProperties properties;

    private final ScriptRuntime runtime;

    private final AtomicReference<Map<String, ParserScript>> byApp = new AtomicReference<>(Map.of());

    private volatile boolean loaded;

    /**
     * Creates the registry, scripts are loaded by {@link #load()}.
     *
     * @param properties configuration
     * @param runtime the script runtime
     */
    public ParserRegistry(LogGuyProperties properties, ScriptRuntime runtime) {
        this.properties = properties;
        this.runtime = runtime;
    }

    /**
     * Loads the parser of every configured application. Nothing is changed if a script has errors.
     *
     * @return the errors, empty if everything was loaded
     */
    public List<String> load() {
        Map<String, ParserScript> scripts = new HashMap<>();
        Map<String, ParserScript> byName = new HashMap<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, AppProperties> app : properties.apps().entrySet()) {
            String name = parserName(app.getKey(), app.getValue());
            try {
                ParserScript script = byName.get(name);
                if (script == null) {
                    script = ParserScript.load(runtime, name, source(name));
                    byName.put(name, script);
                }
                scripts.put(app.getKey(), script);
            } catch (ScriptException | IOException e) {
                errors.add("app " + app.getKey() + ": " + e.getMessage());
            }
        }
        if (errors.isEmpty()) {
            byApp.set(Map.copyOf(scripts));
            loaded = true;
            LOGGER.info("loaded parsers for {} applications", scripts.size());
        }
        return errors;
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

    private static String parserName(String app, AppProperties properties) {
        return properties == null || properties.parser() == null ? app : properties.parser();
    }

    /**
     * Reads the code of a parser from the script directory, or the template of the same name from the JAR.
     *
     * @param name name of the parser
     * @return the code
     * @throws IOException if neither exists
     */
    public String source(String name) throws IOException {
        Path file = properties.scripts().dir().resolve("parser").resolve(name + ".js");
        if (Files.exists(file)) {
            return Files.readString(file);
        }
        try (InputStream in = ParserRegistry.class.getResourceAsStream(TEMPLATES + name + ".js")) {
            if (in == null) {
                throw new IOException("parser script " + file + " not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads a template from the JAR.
     *
     * @param name name of the template, e.g. {@code mycore-log4j}
     * @return the code
     * @throws IOException if the template does not exist
     */
    public static String template(String name) throws IOException {
        try (InputStream in = ParserRegistry.class.getResourceAsStream(TEMPLATES + name + ".js")) {
            if (in == null) {
                throw new IOException("template " + name + " not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
