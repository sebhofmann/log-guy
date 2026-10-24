package de.gbv.logguy.server.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * @param users users of the GUI and the API with their rights
 * @param ingest limits of the stream API
 * @param hot how {@code hot/} is written
 * @param scripts script directory and limits for scripts
 * @param solr connection to the SolrCloud
 * @param indexer settings of the indexer
 * @param search limits of the search API
 * @param retention nightly cleanup
 * @param serverLog the own log of log-guy
 * @param mcp the MCP endpoint
 * @param archive how {@code archive/} is written
 * @param reindex indexing again from {@code hot/} and loading older days from {@code archive/}
 * @param alerts checking events against the alert rules
 * @param notifications targets for notifications of alerts, mail and webhooks
 */
@ConfigurationProperties("log-guy")
public record LogGuyProperties(Path dataDir, ZoneId zone, Map<String, AppProperties> apps,
    List<TokenProperties> tokens, List<UserProperties> users, IngestProperties ingest, HotProperties hot,
    ScriptProperties scripts, SolrProperties solr, IndexerProperties indexer, SearchProperties search,
    RetentionProperties retention, ServerLogProperties serverLog, McpProperties mcp, ArchiveProperties archive,
    ReindexProperties reindex, AlertProperties alerts, NotifyProperties notifications, BackupProperties backup) {

    /**
     * Normalizes missing collections to empty ones.
     */
    public LogGuyProperties {
        apps = apps == null ? Map.of() : Map.copyOf(apps);
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
        users = users == null ? List.of() : List.copyOf(users);
        notifications = notifications == null
            ? new NotifyProperties(null, null, null, null, 1, Duration.ZERO, Duration.ofSeconds(10))
            : notifications;
        backup = backup == null ? new BackupProperties(null, "-", null) : backup;
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
     * Directory with the filtered log files, kept forever.
     *
     * @return {@code <data-dir>/archive}
     */
    public Path archiveDir() {
        return dataDir.resolve("archive");
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
     * Directory with the own log of log-guy.
     *
     * @return {@code <data-dir>/logs}
     */
    public Path logDir() {
        return dataDir.resolve("logs");
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
     * @param activate whether an MCP token may activate parsers and rules itself
     */
    public record TokenProperties(String name, TokenKind kind, String host, List<String> apps, String user,
        String hash, boolean activate) {
    }

    /**
     * A right that is granted per application.
     */
    public enum Right {
        /** Fast search, timeline and context with clear values. */
        READ,
        /** Like {@link #READ}, but only pseudonymized. */
        READ_PSEUDONYMIZED,
        /** Archive search and loading archived days into the index. */
        ARCHIVE,
        /** Solr commands, lifecycle state, rebuilds. Implies all other rights. */
        ADMIN,
        /** Edit, test and activate the scripts of the application. */
        SCRIPTS
    }

    /**
     * A user of the GUI and the API.
     *
     * @param name login name
     * @param passwordHash Argon2 hash of the password, {@code $argon2id$...}
     * @param grants rights per application pattern
     */
    public record UserProperties(String name, String passwordHash, List<GrantProperties> grants) {

        /**
         * Normalizes missing grants to an empty list.
         */
        public UserProperties {
            grants = grants == null ? List.of() : List.copyOf(grants);
        }
    }

    /**
     * Rights for the applications matching some patterns.
     *
     * @param apps application names or patterns like {@code digibib*}
     * @param rights the granted rights
     */
    public record GrantProperties(List<String> apps, Set<Right> rights) {

        /**
         * Normalizes missing values to empty collections.
         */
        public GrantProperties {
            apps = apps == null ? List.of() : List.copyOf(apps);
            rights = rights == null ? Set.of() : Set.copyOf(rights);
        }
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
     * How {@code archive/} is written.
     *
     * @param enabled whether the archive writer runs in this process
     * @param threads number of writer threads, each with its own script contexts
     * @param pollInterval how often files in {@code hot/} are checked for new frames
     * @param compressionLevel zstd compression level
     * @param hotDays days a file stays in {@code hot/} after its last event
     * @param placeholder written instead of a line whose filter failed, {@code {filter}} and {@code {offset}} are
     *     replaced
     * @param fieldPlaceholder indexed instead of the message of an event whose {@code fields(event)} failed,
     *     {@code {filter}} is replaced
     * @param search limits of the archive search
     */
    public record ArchiveProperties(boolean enabled, int threads, Duration pollInterval, int compressionLevel,
        int hotDays, String placeholder, String fieldPlaceholder, ArchiveSearchProperties search) {
    }

    /**
     * Limits of the archive search.
     *
     * @param threads threads that decompress and search frames, shared by all jobs
     * @param maxJobs running jobs per user
     * @param defaultRange time range searched if the request has no {@code from}
     * @param maxRange largest time range of a job
     * @param maxHits a job stops when it has found this many lines
     * @param maxRuntime a job stops after this time
     * @param contextLines lines before and after a hit if the request does not say
     * @param maxContextLines largest number of context lines in each direction
     * @param maxLineLength longer lines of a hit are cut
     * @param keep how long a finished job and its hits stay available
     */
    public record ArchiveSearchProperties(int threads, int maxJobs, Duration defaultRange, Duration maxRange,
        int maxHits, Duration maxRuntime, int contextLines, int maxContextLines, int maxLineLength, Duration keep) {
    }

    /**
     * Script directory and limits.
     *
     * @param dir directory with {@code parser/}, {@code rule/}, {@code lifecycle/} and {@code pseudonym/}
     * @param eventTimeout time after which a single script call is cancelled
     * @param maxSize largest script that can be saved
     * @param testLines most lines a test reads from its source
     * @param testEvents most events a test returns for display
     */
    public record ScriptProperties(Path dir, Duration eventTimeout, DataSize maxSize, int testLines, int testEvents) {
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
     * @param roundSize bytes of one file indexed before other files get their turn
     * @param provisionalAfter time without new lines after which a growing event is indexed provisionally
     * @param maxEventSize events longer than this are cut
     * @param indexDays global number of days kept in the index
     * @param statsInterval how often the throughput is logged
     */
    public record IndexerProperties(boolean enabled, int threads, Duration pollInterval, int batchSize,
        DataSize readSize, DataSize roundSize, Duration provisionalAfter, DataSize maxEventSize, int indexDays,
        Duration statsInterval) {
    }

    /**
     * Limits of the search API.
     *
     * @param defaultRange time range searched if the request has no {@code from}
     * @param defaultRows events per page if the request has no {@code rows}
     * @param maxRows largest page
     * @param maxBars largest number of bars of a histogram, the interval is chosen to stay below
     * @param splitLimit how many values of the split field get their own series in the histogram
     * @param facetLimit values per field in the facets if the request has no {@code limit}
     * @param maxFacetLimit largest {@code limit} for facets
     * @param contextLines lines before and after an event if the request does not say
     * @param maxContextLines largest number of context lines in each direction
     * @param contextBytes how far the context reads in each direction at most
     */
    public record SearchProperties(Duration defaultRange, int defaultRows, int maxRows, int maxBars, int splitLimit,
        int facetLimit, int maxFacetLimit, int contextLines, int maxContextLines, DataSize contextBytes) {
    }

    /**
     * The backup of {@code archive/}, {@code state/}, {@code audit/} and the configuration into a directory, from
     * where a backup tool copies it further. {@code hot/} is not part of it.
     *
     * @param dir target directory, {@code null} switches the backup off
     * @param cron when it runs, a Spring cron expression in the zone of log-guy, {@code -} only on request
     * @param configDir the configuration directory with {@code application.yml} and scripts, {@code null} to skip it
     */
    public record BackupProperties(Path dir, String cron, Path configDir) {

        /**
         * Whether a backup directory is set.
         *
         * @return {@code true} if backups are switched on
         */
        public boolean enabled() {
            return dir != null && !dir.toString().isBlank();
        }
    }

    /**
     * The nightly cleanup.
     *
     * @param cron when it runs, a Spring cron expression in the zone of log-guy
     * @param expungeDeletes whether Solr removes deleted documents physically afterwards
     */
    public record RetentionProperties(String cron, boolean expungeDeletes) {
    }

    /**
     * Indexing again and loading older days from the archive.
     *
     * @param expiresAfter how long events loaded from the archive stay in the index
     * @param expireCron when events whose time is over are deleted, a Spring cron expression in the zone of log-guy,
     *     {@code -} for never
     * @param maxRange longest time range one job may load from the archive
     * @param keepJobs finished jobs kept for their status
     */
    public record ReindexProperties(Duration expiresAfter, String expireCron, Duration maxRange, int keepJobs) {
    }

    /**
     * The own log of log-guy.
     *
     * @param ringSize entries kept in memory for the page Server-Log
     * @param keepDays days the rotated log files in {@code logs/} are kept
     * @param pattern Log4j pattern of the log file
     */
    public record ServerLogProperties(int ringSize, int keepDays, String pattern) {
    }

    /**
     * The MCP endpoint.
     *
     * @param path path of the endpoint
     * @param sessionIdleTimeout a session and its pseudonyms are dropped after this time without a request
     * @param maxSessions open sessions at most, over all tokens
     * @param maxEvents events per tool call at most
     * @param maxText characters per text at most, longer texts are cut
     */
    public record McpProperties(String path, Duration sessionIdleTimeout, int maxSessions, int maxEvents,
        int maxText) {
    }

    /**
     * Alerts from the rules in {@code scripts/rule/}.
     *
     * @param enabled whether events are checked at all
     * @param maxAge older events are not checked, e.g. when backfilling or indexing again
     * @param defaultCooldown cooldown of rules without {@code meta.cooldown}
     * @param tickInterval how often thresholds, silence and cooldowns are evaluated and the state is written
     * @param keep acknowledged and resolved alerts are dropped after this time without change
     * @param maxText characters of the event line and the message of an alert at most
     * @param testRange time range of a rule test
     * @param testMaxEvents events a rule test reads at most
     * @param testSamples matching events and simulated alerts a rule test shows at most
     * @param streamDuration a stream ends after this time, the client reconnects, must stay below the async timeout
     * @param streamPing interval of keep alive comments in a stream
     */
    public record AlertProperties(boolean enabled, Duration maxAge, Duration defaultCooldown, Duration tickInterval,
        Duration keep, int maxText, Duration testRange, int testMaxEvents, int testSamples, Duration streamDuration,
        Duration streamPing) {
    }

    /**
     * Where notifications of alerts go. The SMTP server is configured with Spring's {@code spring.mail.*}.
     *
     * @param baseUrl address of the GUI for links in notifications, e.g. {@code https://log-guy.example.org},
     *     {@code null} for no links
     * @param from sender of mails
     * @param mail mail targets by name, a rule names them as {@code mail:<name>}
     * @param webhook webhook targets by name, a rule names them as {@code webhook:<name>}
     * @param attempts how often a notification is tried before it is given up
     * @param retryDelay wait before the second attempt, doubled for every further one
     * @param timeout connect and request timeout of webhooks
     */
    public record NotifyProperties(String baseUrl, String from, Map<String, MailTarget> mail,
        Map<String, WebhookTarget> webhook, int attempts, Duration retryDelay, Duration timeout) {

        /**
         * Normalizes missing targets to none.
         */
        public NotifyProperties {
            mail = mail == null ? Map.of() : Map.copyOf(mail);
            webhook = webhook == null ? Map.of() : Map.copyOf(webhook);
            if (baseUrl != null && baseUrl.isBlank()) {
                baseUrl = null;
            }
            while (baseUrl != null && baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
        }
    }

    /**
     * A mail target.
     *
     * @param to recipients
     * @param includeText whether text and event line are sent, {@code false} sends only rule, application and link;
     *     {@code null} for {@code true}
     */
    public record MailTarget(List<String> to, Boolean includeText) {

        /**
         * Normalizes missing recipients to none.
         */
        public MailTarget {
            to = to == null ? List.of() : List.copyOf(to);
        }

        /**
         * Whether text and event line are sent.
         *
         * @return {@code include-text}, {@code true} if not set
         */
        public boolean withText() {
            return includeText == null || includeText;
        }
    }

    /**
     * A webhook target, called with a JSON body per {@code POST}.
     *
     * @param url the address
     * @param headers additional request headers, e.g. a token
     * @param includeText whether text and event line are sent, {@code null} for {@code true}
     */
    public record WebhookTarget(String url, Map<String, String> headers, Boolean includeText) {

        /**
         * Normalizes missing headers to none.
         */
        public WebhookTarget {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        /**
         * Whether text and event line are sent.
         *
         * @return {@code include-text}, {@code true} if not set
         */
        public boolean withText() {
            return includeText == null || includeText;
        }
    }
}
