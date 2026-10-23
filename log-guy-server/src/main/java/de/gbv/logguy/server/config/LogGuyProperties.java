package de.gbv.logguy.server.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Configuration of log-guy, read from {@code application.yml} below the prefix {@code log-guy}. The defaults are in
 * the {@code application.yml} inside the JAR, the operator overrides them in {@code /etc/log-guy/application.yml}.
 *
 * @param dataDir data directory with {@code hot/}, {@code archive/}, {@code state/} and {@code audit/}
 * @param zone time zone for the date in file names and for log timestamps without zone
 * @param apps applications by name
 * @param tokens access tokens for agents, API and MCP
 * @param ingest limits of the stream API
 * @param hot how {@code hot/} is written
 * @param scripts script directory and limits for scripts
 * @param solr connection to the SolrCloud
 * @param indexer settings of the indexer
 */
@ConfigurationProperties("log-guy")
public record LogGuyProperties(Path dataDir, ZoneId zone, Map<String, AppProperties> apps,
    List<TokenProperties> tokens, IngestProperties ingest, HotProperties hot, ScriptProperties scripts,
    SolrProperties solr, IndexerProperties indexer) {

    /**
     * Normalizes missing collections to empty ones.
     */
    public LogGuyProperties {
        apps = apps == null ? Map.of() : Map.copyOf(apps);
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
    }

    /**
     * Directory with the original log files of the last days.
     *
     * @return {@code <data-dir>/hot}
     */
    public Path hotDir() {
        return dataDir.resolve("hot");
    }

    /**
     * Directory with state that survives a restart, e.g. the indexer progress.
     *
     * @return {@code <data-dir>/state}
     */
    public Path stateDir() {
        return dataDir.resolve("state");
    }

    /**
     * Settings of one application.
     *
     * @param indexDays how many days of this application stay in the index, overrides the global value
     * @param parser name of the parser script in {@code scripts/parser/}, defaults to the application name
     */
    public record AppProperties(Integer indexDays, String parser) {
    }

    /**
     * Kind of an access token.
     */
    public enum TokenKind {
        /** Belongs to a host, may only write the applications of this host. */
        INGEST,
        /** Belongs to a user, may do what the user may do. */
        API,
        /** Belongs to a user, everything pseudonymized. */
        MCP
    }

    /**
     * An access token. Only the hash of the token is configured.
     *
     * @param name name for logs and audit
     * @param kind kind of the token
     * @param host host of an ingest token
     * @param apps applications of an ingest token
     * @param user user of an API or MCP token
     * @param hash {@code sha256:<hex>} of the token
     */
    public record TokenProperties(String name, TokenKind kind, String host, List<String> apps, String user,
        String hash) {
    }

    /**
     * Limits of the stream API.
     *
     * @param maxChunkSize largest accepted chunk
     * @param maxConcurrent number of chunks processed at the same time, more are answered with 429
     * @param retryAfter value of the {@code Retry-After} header for 429
     */
    public record IngestProperties(DataSize maxChunkSize, int maxConcurrent, Duration retryAfter) {
    }

    /**
     * How {@code hot/} is written.
     *
     * @param frameSize uncompressed size after which a zstd frame is written
     * @param frameMaxAge age of the oldest pending byte after which a frame is written anyway
     * @param flushInterval how often pending bytes are checked for their age
     * @param compressionLevel zstd compression level
     */
    public record HotProperties(DataSize frameSize, Duration frameMaxAge, Duration flushInterval,
        int compressionLevel) {
    }

    /**
     * Script directory and limits.
     *
     * @param dir directory with {@code parser/}, {@code rule/}, {@code lifecycle/} and {@code pseudonym/}
     * @param eventTimeout time after which a single script call is cancelled
     * @param maxHeapPerContext heap a script context may use, if the runtime supports the limit
     */
    public record ScriptProperties(Path dir, Duration eventTimeout, DataSize maxHeapPerContext) {
    }

    /**
     * Connection to the SolrCloud.
     *
     * @param urls base URLs of the Solr nodes, e.g. {@code http://solr1:8983/solr}
     * @param user user for basic authentication, empty for none
     * @param passwordFile file with the password of {@code user}
     * @param configset name of the configset, carries the version
     * @param collection name of the collection
     * @param alias name of the alias that is searched and written
     * @param replicationFactor replicas of the collection
     * @param timeout timeout for requests
     */
    public record SolrProperties(List<String> urls, String user, Path passwordFile, String configset,
        String collection, String alias, int replicationFactor, Duration timeout) {
    }

    /**
     * Settings of the indexer.
     *
     * @param enabled whether the indexer runs in this process
     * @param threads number of indexer threads, each with its own script context
     * @param pollInterval how often files are checked for new bytes
     * @param batchSize documents per request to Solr
     * @param readSize bytes read from {@code hot/} per step
     * @param provisionalAfter time without new lines after which a growing event is indexed provisionally
     * @param maxEventSize events longer than this are cut
     * @param indexDays global number of days kept in the index
     */
    public record IndexerProperties(boolean enabled, int threads, Duration pollInterval, int batchSize,
        DataSize readSize, Duration provisionalAfter, DataSize maxEventSize, int indexDays) {
    }
}
