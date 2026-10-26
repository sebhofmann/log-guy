package de.gbv.logguy.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Configuration of the agent, read from {@code agent.yml} and environment variables on top of the defaults in the JAR.
 * An environment variable {@code LOG_GUY_<KEY>} sets the key of {@code agent.yml} with {@code _} for {@code -}, e.g.
 * {@code LOG_GUY_SERVER}, {@code LOG_GUY_TOKEN} or {@code LOG_GUY_TOKEN_FILE}. Sources are numbered as
 * {@code LOG_GUY_SOURCES_<n>_APP}, {@code _LIVE} and {@code _ROTATED} and replace the sources of the file.
 *
 * @param server base URL of log-guy
 * @param token the ingest token, read from {@code token-file}
 * @param host name of this host, must match the token. Defaults to the host name.
 * @param chunkSize largest chunk sent at once
 * @param sendInterval how often the live files are checked
 * @param rotatedScanInterval how often the rotated files are checked
 * @param connectTimeout connect timeout
 * @param requestTimeout timeout of one request
 * @param retryDelay wait time after an error
 * @param sources the log files
 */
public record AgentConfig(URI server, String token, String host, int chunkSize, Duration sendInterval,
    Duration rotatedScanInterval, Duration connectTimeout, Duration requestTimeout, Duration retryDelay,
    List<Source> sources) {

    /** Default location of the configuration file. */
    public static final Path DEFAULT_FILE = Path.of("/etc/log-guy-agent/agent.yml");

    /** Prefix of the environment variables read as configuration. */
    public static final String ENV_PREFIX = "LOG_GUY_";

    private static final Pattern ENV_SOURCE = Pattern.compile("SOURCES_(\\d+)_(APP|LIVE|ROTATED)");

    /**
     * One log source of an application.
     *
     * @param app name of the application in log-guy
     * @param live path of the live file, may be null
     * @param rotated glob pattern of the rotated files, may be null
     */
    public record Source(String app, Path live, String rotated) {
    }

    /**
     * Reads the configuration from the file and the environment of the process.
     *
     * @param file the configuration file, may be missing if everything is given otherwise
     * @param overrides values that replace those from the file and the environment, e.g. from the command line
     * @return the configuration
     * @throws IOException if a file cannot be read
     */
    public static AgentConfig load(Path file, Map<String, Object> overrides) throws IOException {
        return load(file, System.getenv(), overrides);
    }

    /**
     * Reads the configuration. Defaults, the file, the environment and the overrides replace each other in this order.
     *
     * @param file the configuration file, may be missing if everything is given otherwise
     * @param env environment variables, only those starting with {@value #ENV_PREFIX} are used
     * @param overrides values that replace all others, e.g. from the command line
     * @return the configuration
     * @throws IOException if a file cannot be read
     */
    public static AgentConfig load(Path file, Map<String, String> env, Map<String, Object> overrides)
        throws IOException {
        Map<String, Object> values = new LinkedHashMap<>(read(AgentConfig.class.getResourceAsStream(
            "/agent-defaults.yml")));
        if (file != null && Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                values.putAll(read(in));
            }
        }
        values.putAll(fromEnv(env));
        values.putAll(overrides);
        return of(values);
    }

    private static Map<String, Object> fromEnv(Map<String, String> env) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> sources = new TreeMap<>();
        env.forEach((name, value) -> {
            if (!name.startsWith(ENV_PREFIX) || value.isEmpty()) {
                return;
            }
            String key = name.substring(ENV_PREFIX.length());
            Matcher source = ENV_SOURCE.matcher(key);
            if (source.matches()) {
                sources.computeIfAbsent(Integer.valueOf(source.group(1)), i -> new LinkedHashMap<>())
                    .put(source.group(2).toLowerCase(Locale.ROOT), value);
            } else {
                values.put(key.toLowerCase(Locale.ROOT).replace('_', '-'), value);
            }
        });
        if (!sources.isEmpty()) {
            values.put("sources", new ArrayList<>(sources.values()));
        }
        return values;
    }

    private static Map<String, Object> read(InputStream in) throws IOException {
        try (Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            Map<String, Object> map = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            return map == null ? Map.of() : map;
        }
    }

    @SuppressWarnings("unchecked")
    private static AgentConfig of(Map<String, Object> values) throws IOException {
        Object server = require(values, "server");
        String token = values.containsKey("token") ? values.get("token").toString()
            : Files.readString(Path.of(require(values, "token-file").toString())).trim();
        String host = values.get("host") == null ? InetAddress.getLocalHost().getHostName()
            : values.get("host").toString();
        List<Source> sources = new ArrayList<>();
        for (Map<String, Object> source : (List<Map<String, Object>>) require(values, "sources")) {
            Object live = source.get("live");
            Object rotated = source.get("rotated");
            sources.add(new Source(require(source, "app").toString(), live == null ? null : Path.of(live.toString()),
                rotated == null ? null : rotated.toString()));
        }
        return new AgentConfig(URI.create(server.toString().replaceAll("/+$", "")), token, host,
            Integer.parseInt(require(values, "chunk-size").toString()), duration(values, "send-interval"),
            duration(values, "rotated-scan-interval"), duration(values, "connect-timeout"),
            duration(values, "request-timeout"), duration(values, "retry-delay"), List.copyOf(sources));
    }

    private static Duration duration(Map<String, Object> values, String key) {
        return Duration.parse(require(values, key).toString());
    }

    private static Object require(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing configuration value " + key);
        }
        return value;
    }
}
