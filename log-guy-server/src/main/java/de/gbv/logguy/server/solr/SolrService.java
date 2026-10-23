package de.gbv.logguy.server.solr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudHttp2SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.common.SolrInputDocument;
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
