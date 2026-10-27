package de.gbv.logguy.server.ingest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.IngestProtocol;
import de.gbv.logguy.common.TestLogs;
import de.gbv.logguy.server.security.TokenRegistry;

@SpringBootTest
@AutoConfigureMockMvc
class StreamControllerTest {

    private static final String TOKEN = "lg_ingest_test";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("log-guy.data-dir", dataDir::toString);
        registry.add("log-guy.indexer.enabled", () -> "false");
        registry.add("log-guy.apps.digibib.parser", () -> "mycore-log4j");
        registry.add("log-guy.apps.digibib-apache.index-days", () -> "7");
        registry.add("log-guy.tokens[0].name", () -> "agent-app1");
        registry.add("log-guy.tokens[0].kind", () -> "ingest");
        registry.add("log-guy.tokens[0].host", () -> "app1");
        registry.add("log-guy.tokens[0].apps", () -> "digibib,digibib-apache");
        registry.add("log-guy.tokens[0].hash", () -> TokenRegistry.hash(TOKEN));
    }

    @Autowired
    MockMvc mvc;

    private final byte[] sample = TestLogs.mycoreSample();

    private MockHttpServletRequestBuilder chunk(String fingerprint, long offset, byte[] data, String app) {
        return chunk(fingerprint, offset, data, app, TOKEN);
    }

    private MockHttpServletRequestBuilder chunk(String fingerprint, long offset, byte[] data, String app,
        String token) {
        return post(IngestProtocol.STREAMS_PATH + "/" + fingerprint + "/chunks")
            .param(IngestProtocol.PARAM_OFFSET, Long.toString(offset))
            .header("Authorization", "Bearer " + token)
            .header(IngestProtocol.HEADER_HOST, "app1")
            .header(IngestProtocol.HEADER_APP, app)
            .header(IngestProtocol.HEADER_FILE, "/logs/mycore-app.log")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(data);
    }

    @Test
    void streamLifecycle() throws Exception {
        byte[] data = Arrays.copyOf(sample, 50_000);
        String fingerprint = Fingerprint.of(data);
        String path = IngestProtocol.STREAMS_PATH + "/" + fingerprint;

        mvc.perform(get(path).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isNotFound());
        mvc.perform(chunk(fingerprint, 10, Arrays.copyOfRange(data, 10, 2000), "digibib"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.length").value(0));
        mvc.perform(chunk(fingerprint, 0, Arrays.copyOf(data, 30_000), "digibib"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length").value(30_000))
            .andExpect(jsonPath("$.closed").value(false));
        // overlapping chunk is cut to the new bytes
        mvc.perform(chunk(fingerprint, 20_000, Arrays.copyOfRange(data, 20_000, 50_000), "digibib"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length").value(50_000));
        mvc.perform(get(path).header("Authorization", "Bearer " + TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length").value(50_000));
        mvc.perform(post(path + "/close").param(IngestProtocol.PARAM_LENGTH, "40000")
            .header("Authorization", "Bearer " + TOKEN)
            .header(IngestProtocol.HEADER_HOST, "app1")
            .header(IngestProtocol.HEADER_APP, "digibib")).andExpect(status().isConflict());
        mvc.perform(post(path + "/close").param(IngestProtocol.PARAM_LENGTH, "50000")
            .header("Authorization", "Bearer " + TOKEN)
            .header(IngestProtocol.HEADER_HOST, "app1")
            .header(IngestProtocol.HEADER_APP, "digibib"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.closed").value(true));
        // the same file under another application is refused
        mvc.perform(chunk(fingerprint, 0, data, "digibib-apache")).andExpect(status().isForbidden());
    }

    @Test
    void wrongFingerprintIsRejected() throws Exception {
        byte[] data = Arrays.copyOfRange(sample, 200_000, 205_000);
        String fingerprint = Fingerprint.of(Arrays.copyOfRange(sample, 100_000, 105_000));
        mvc.perform(chunk(fingerprint, 0, data, "digibib")).andExpect(status().isBadRequest());
    }

    @Test
    void tokenIsChecked() throws Exception {
        byte[] data = Arrays.copyOfRange(sample, 1000, 5000);
        String fingerprint = Fingerprint.of(data);
        mvc.perform(chunk(fingerprint, 0, data, "leopoldina")).andExpect(status().isForbidden());
        mvc.perform(chunk(fingerprint, 0, data, "digibib", "wrong"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(IngestProtocol.STREAMS_PATH + "/" + fingerprint + "/chunks").param("offset", "0")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(data)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenTellsHostAndApplications() throws Exception {
        mvc.perform(get(IngestProtocol.STREAMS_PATH).header("Authorization", "Bearer " + TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("agent-app1"))
            .andExpect(jsonPath("$.host").value("app1"))
            .andExpect(jsonPath("$.apps[0]").value("digibib"))
            .andExpect(jsonPath("$.apps[1]").value("digibib-apache"));
        mvc.perform(get(IngestProtocol.STREAMS_PATH).header("Authorization", "Bearer wrong"))
            .andExpect(status().isUnauthorized());
    }
}
