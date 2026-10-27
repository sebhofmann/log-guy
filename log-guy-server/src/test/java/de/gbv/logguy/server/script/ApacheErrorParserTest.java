package de.gbv.logguy.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ApacheErrorParserTest {

    private static ScriptRuntime runtime;

    private static ParserScript apache;

    @BeforeAll
    static void setUp() throws Exception {
        runtime = new ScriptRuntime(Duration.ofMillis(50), Duration.ofSeconds(5));
        apache = ParserScript.load(runtime, "apache-error", ParserRegistry.template("apache-error"));
    }

    @AfterAll
    static void tearDown() {
        runtime.close();
    }

    private static ParsedEvent parse(String line) throws Exception {
        try (Parser parser = new Parser(runtime, apache)) {
            ParsedEvent event = parser.parse(line);
            assertNull(event.error(), line);
            return event;
        }
    }

    @Test
    void proxyErrorWithClientAndReferer() throws Exception {
        assertFalse(apache.hasIsStart());
        ParsedEvent event = parse("[Sat Oct 24 05:04:13.277751 2026] [proxy_http:error] [pid 8:tid 11] [client"
            + " 172.17.0.1:50248] AH01114: HTTP: failed to make connection to backend: 127.0.0.1, referer:"
            + " https://example.org/suche?q=a");
        assertEquals(Instant.parse("2026-10-24T03:04:13.277Z"), event.ts(), "Solr keeps milliseconds");
        Map<String, Object> fields = event.fields();
        assertEquals("error", fields.get("level"));
        assertEquals("proxy_http", fields.get("module"));
        assertEquals("AH01114", fields.get("code"));
        assertEquals("172.17.0.1", fields.get("ip"));
        assertEquals("https://example.org/suche?q=a", fields.get("referer"));
        assertNull(fields.get("os_error"));
    }

    @Test
    void osErrorBeforeTheClientAndLogin() throws Exception {
        Map<String, Object> fields = parse("[Sat Oct 24 05:04:13.298352 2026] [authn_file:error] [pid 13:tid 32]"
            + " (2)No such file or directory: [client 172.17.0.1:50276] AH01620: Could not open password file:"
            + " /usr/local/apache2/conf/htpasswd").fields();
        assertEquals("No such file or directory", fields.get("os_error"));
        assertEquals("172.17.0.1", fields.get("ip"));
        assertEquals("AH01620", fields.get("code"));

        Map<String, Object> login = parse("[Sat Oct 24 05:04:22.669917 2026] [auth_basic:error] [pid 8:tid 20]"
            + " [client 2001:db8::7:36010] AH01617: user mueller: authentication failure for \"/geheim/\":"
            + " Password Mismatch").fields();
        assertEquals("mueller", login.get("login"));
        assertEquals("2001:db8::7", login.get("ip"));
    }

    @Test
    void apache22FormatAndLinesWithoutTime() throws Exception {
        ParsedEvent old = parse("[Wed Oct 11 14:32:52 2000] [error] [client 127.0.0.1] client denied by server"
            + " configuration: /export/home/live/ap/htdocs/test");
        assertEquals(Instant.parse("2000-10-11T12:32:52Z"), old.ts());
        assertEquals("error", old.fields().get("level"));
        assertNull(old.fields().get("module"));
        assertEquals("127.0.0.1", old.fields().get("ip"));

        ParsedEvent startup = parse("AH00558: httpd: Could not reliably determine the server's fully qualified"
            + " domain name, using 172.17.0.3. Set the 'ServerName' directive globally to suppress this message");
        assertNull(startup.ts());
        assertEquals(Map.of(), startup.fields());
    }

    @Test
    void wholeSample() throws Exception {
        String sample;
        try (InputStream in = ApacheErrorParserTest.class.getResourceAsStream("/logs/apache-error.log")) {
            sample = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String line : sample.split("\n")) {
            ParsedEvent event = parse(line);
            assertNotNull(event.ts(), line);
            assertNotNull(event.fields().get("level"), line);
            assertNotNull(event.fields().get("code"), line);
        }
    }
}
