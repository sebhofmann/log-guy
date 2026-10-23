package de.gbv.logguy.server.solr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudHttp2SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.ContentStreamBase;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import de.gbv.logguy.server.config.LogGuyProperties.SolrProperties;

/**
 * Connection to the SolrCloud and setup of configset, collection and alias.
 */
public class SolrService implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String CONFIGSET_LOCATION = "solr/";

    private final SolrProperties properties;

    private final CloudHttp2SolrClient client;

    /**
     * Creates the client. Requests go to the nodes of {@code urls}; if one is down, the next is used.
     *
     * @param properties connection settings
     * @throws IOException if the password file cannot be read
     */
    public SolrService(SolrProperties properties) throws IOException {
        this.properties = properties;
        long timeout = properties.timeout().toMillis();
        Http2SolrClient.Builder http = new Http2SolrClient.Builder()
            .withConnectionTimeout(Math.min(timeout, 10_000), TimeUnit.MILLISECONDS)
            .withIdleTimeout(timeout, TimeUnit.MILLISECONDS)
            .withRequestTimeout(timeout, TimeUnit.MILLISECONDS);
        if (properties.user() != null && !properties.user().isBlank()) {
            String password = Files.readString(properties.passwordFile()).trim();
            http.withBasicAuthCredentials(properties.user(), password);
        }
        this.client = new CloudHttp2SolrClient.Builder(properties.urls())
            .withInternalClientBuilder(http)
            .withDefaultCollection(properties.alias())
            .build();
    }

    /**
     * The client, its default collection is the alias.
     *
     * @return the client
     */
    public SolrClient client() {
        return client;
    }

    /**
     * Adds documents to the alias. Visible after the next soft commit of the collection.
     *
     * @param documents the documents
     * @throws IOException on network errors
     * @throws SolrServerException on errors reported by Solr
     */
    public void add(Collection<SolrInputDocument> documents) throws IOException, SolrServerException {
        client.add(properties.alias(), documents);
    }

    /**
     * Uploads the configset, creates the collection and sets the alias. Steps already done are skipped, so the command
     * can run again.
     *
     * @return what was done, one line per step
     * @throws IOException on network errors
     * @throws SolrServerException on errors reported by Solr
     */
    public List<String> init() throws IOException, SolrServerException {
        List<String> steps = new java.util.ArrayList<>();
        List<String> configsets = new ConfigSetAdminRequest.List().process(client).getConfigSets();
        if (configsets.contains(properties.configset())) {
            steps.add("configset " + properties.configset() + " exists");
        } else {
            new ConfigSetAdminRequest.Upload()
                .setConfigSetName(properties.configset())
                .setUploadStream(new ContentStreamBase.ByteArrayStream(configsetZip(properties.configset()),
                    properties.configset() + ".zip", "application/zip"))
                .process(client);
            steps.add("configset " + properties.configset() + " uploaded");
        }
        List<String> collections = CollectionAdminRequest.listCollections(client);
        if (collections.contains(properties.collection())) {
            steps.add("collection " + properties.collection() + " exists");
        } else {
            CollectionAdminRequest
                .createCollection(properties.collection(), properties.configset(), 1, properties.replicationFactor())
                .process(client);
            steps.add("collection " + properties.collection() + " created with replication factor "
                + properties.replicationFactor());
        }
        CollectionAdminRequest.ListAliases listAliases = new CollectionAdminRequest.ListAliases();
        CollectionAdminResponse aliasResponse = listAliases.process(client);
        Map<String, String> aliases = aliasResponse.getAliases();
        if (properties.collection().equals(aliases.get(properties.alias()))) {
            steps.add("alias " + properties.alias() + " points to " + properties.collection());
        } else {
            CollectionAdminRequest.createAlias(properties.alias(), properties.collection()).process(client);
            steps.add("alias " + properties.alias() + " set to " + properties.collection());
        }
        steps.forEach(LOGGER::info);
        return steps;
    }

    /**
     * The replicas of the collection behind the alias with their size, asked from every node.
     *
     * @return one entry per replica
     * @throws IOException on network errors
     * @throws SolrServerException on errors reported by Solr
     */
    public List<ReplicaInfo> replicas() throws IOException, SolrServerException {
        String alias = new CollectionAdminRequest.ListAliases().process(client).getAliases().get(properties.alias());
        String collection = alias != null ? alias : properties.collection();
        DocCollection state = client.getClusterStateProvider().getCollection(collection);
        List<ReplicaInfo> result = new ArrayList<>();
        if (state == null) {
            return result;
        }
        for (Slice slice : state.getSlices()) {
            for (Replica replica : slice.getReplicas()) {
                long size = -1;
                long docs = -1;
                long deleted = -1;
                String error = null;
                if (replica.getState() == Replica.State.ACTIVE) {
                    try {
                        NamedList<?> metrics = coreMetrics(replica.getBaseUrl());
                        String prefix = collection + "_" + slice.getName() + "_";
                        String core = replica.getCoreName();
                        String key = "solr.core." + collection + "." + slice.getName() + "."
                            + (core.startsWith(prefix) ? core.substring(prefix.length()) : core);
                        Object values = metrics == null ? null : metrics.get(key);
                        size = number(entry(values, "INDEX.sizeInBytes"));
                        docs = number(entry(values, "SEARCHER.searcher.numDocs"));
                        deleted = number(entry(values, "SEARCHER.searcher.deletedDocs"));
                    } catch (IOException | SolrServerException | RuntimeException e) {
                        error = e.getMessage();
                    }
                }
                result.add(new ReplicaInfo(collection, slice.getName(), replica.getName(), replica.getNodeName(),
                    replica.getState().toString(), replica.isLeader(), size, docs, deleted, error));
            }
        }
        return result;
    }

    private NamedList<?> coreMetrics(String baseUrl) throws IOException, SolrServerException {
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("group", "core");
        params.set("prefix", "INDEX.sizeInBytes,SEARCHER.searcher.numDocs,SEARCHER.searcher.deletedDocs");
        GenericSolrRequest request = new GenericSolrRequest(SolrRequest.METHOD.GET, "/admin/metrics", params)
            .setRequiresCollection(false);
        request.setBasePath(baseUrl);
        return (NamedList<?>) client.request(request).get("metrics");
    }

    private static Object entry(Object container, String key) {
        return switch (container) {
            case Map<?, ?> map -> map.get(key);
            case NamedList<?> list -> list.get(key);
            case null, default -> null;
        };
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : -1;
    }

    /**
     * A replica of the collection.
     *
     * @param collection the collection
     * @param shard the shard
     * @param name the replica
     * @param node the node it lives on
     * @param state its state, e.g. {@code active}
     * @param leader whether it is the leader of its shard
     * @param sizeBytes size of the index on disk, {@code -1} if unknown
     * @param documents live documents, {@code -1} if unknown
     * @param deletedDocuments deleted documents not yet merged away, {@code -1} if unknown
     * @param error why the numbers are unknown, {@code null} if they are known or the replica is not active
     */
    public record ReplicaInfo(String collection, String shard, String name, String node, String state,
        boolean leader, long sizeBytes, long documents, long deletedDocuments, String error) {
    }

    /**
     * Packs the configset from the JAR as ZIP for the Configsets API.
     *
     * @param name name of the configset, a directory below {@code solr/} in the JAR
     * @return the ZIP
     * @throws IOException if the files cannot be read
     */
    static byte[] configsetZip(String name) throws IOException {
        String prefix = CONFIGSET_LOCATION + name + "/";
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:" + prefix + "**");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int files = 0;
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Resource resource : resources) {
                if (!resource.isReadable() || resource.getFilename() == null || resource.getFilename().isEmpty()) {
                    continue;
                }
                String url = resource.getURL().toString();
                String entry = url.substring(url.lastIndexOf(prefix) + prefix.length());
                if (entry.isEmpty() || entry.endsWith("/")) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(entry));
                try (InputStream in = resource.getInputStream()) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
                files++;
            }
        }
        if (files == 0) {
            throw new IOException("configset " + name + " not found in the JAR");
        }
        return bytes.toByteArray();
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
