package de.gbv.logguy.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.gbv.logguy.common.TestLogs;
import de.gbv.logguy.server.index.EventReader;

class ParserTest {

    private static ScriptRuntime runtime;

    private static ParserScript mycore;

    @BeforeAll
    static void setUp() throws Exception {
        runtime = new ScriptRuntime(Duration.ofMillis(50));
        mycore = ParserScript.load(runtime, "mycore-log4j", ParserRegistry.template("mycore-log4j"));
    }

    @AfterAll
    static void tearDown() {
        runtime.close();
    }

    @Test
    void declaredFields() {
        assertEquals(Set.of("level", "login", "logger", "exc"), mycore.fields().keySet());
        assertEquals(FieldType.STRINGS, mycore.fields().get("exc").type());
        assertTrue(mycore.fields().get("level").facet());
        assertTrue(mycore.hasIsStart());
    }

    @Test
    void parsesLinesWithAndWithoutLogin() throws Exception {
        try (Parser parser = new Parser(runtime, mycore)) {
            ParsedEvent guest = parser.parse("\u001b[m\u001b[32m2026-10-01T00:00:00,152 INFO  guest "
                + "MCRSessionHookFilter: locale/translate/mcr.file.browser.* ip=203.0.113.0");
            assertEquals(Instant.parse("2026-09-30T22:00:00.152Z"), guest.ts());
            assertEquals("INFO", guest.fields().get("level"));
            assertEquals("guest", guest.fields().get("login"));
            assertEquals("MCRSessionHookFilter", guest.fields().get("logger"));
            assertEquals("2026-10-01T00:00:00,152 INFO  guest MCRSessionHookFilter: locale/translate/mcr.file.browser.*"
                + " ip=203.0.113.0", guest.message());

            ParsedEvent anonymous = parser
                .parse("\u001b[32m2026-09-30T00:00:00,223 INFO   MCRServlet: Created new HTTP session: 46940C0A");
            assertNull(anonymous.fields().get("login"));
            assertEquals("MCRServlet", anonymous.fields().get("logger"));
            assertNull(anonymous.error());
        }
    }

    @Test
    void findsExceptionsInStackTraces() throws Exception {
        String event = """
            \u001b[1;31m2026-09-30T00:00:00,834 ERROR  MCRErrorServlet: /accesskey/set.xed: Error 500 occured.
            org.mycore.common.MCRException: Error while transforming!
            \tat org.mycore.frontend.servlets.MCRErrorServlet.doGet(MCRErrorServlet.java:66)
            Caused by: java.lang.Exception: wrapped
            Caused by: java.lang.OutOfMemoryError: Java heap space""";
        try (Parser parser = new Parser(runtime, mycore)) {
            ParsedEvent parsed = parser.parse(event);
            assertEquals("ERROR", parsed.fields().get("level"));
            assertEquals(Set.of("org.mycore.common.MCRException", "java.lang.Exception",
                "java.lang.OutOfMemoryError"), new HashSet<>((List<?>) parsed.fields().get("exc")));
        }
    }

    @Test
    void wholeSample() throws Exception {
        byte[] sample = TestLogs.mycoreSample();
        try (Parser parser = new Parser(runtime, mycore)) {
            EventReader reader = new EventReader(0, 1024 * 1024);
            List<EventReader.RawEvent> events = new ArrayList<>(reader.feed(sample, parser::isStart));
            events.addAll(reader.tail(parser::isStart));
            int lines = new String(sample, StandardCharsets.UTF_8).split("\n").length;
            assertTrue(events.size() < lines, events.size() + " events, " + lines + " lines");
            int withExc = 0;
            for (EventReader.RawEvent event : events) {
                ParsedEvent parsed = parser.parse(event.text());
                assertNull(parsed.error(), event.text());
                assertNotNull(parsed.ts(), event.text());
                assertNotNull(parsed.fields().get("level"), event.text());
                assertNotNull(parsed.fields().get("logger"), event.text());
                assertFalse(parsed.message().contains("\u001b"), event.text());
                if (parsed.fields().containsKey("exc")) {
                    withExc++;
                }
            }
            assertTrue(withExc > 10, "events with exceptions: " + withExc);
        }
    }

    @Test
    void endlessScriptIsCancelled() throws Exception {
        ParserScript spin = ParserScript.load(runtime, "spin", """
            export const fields = { level: { type: "string" } };
            export function parse(text) {
                if (text === "spin") { while (true) {} }
                return { level: "INFO" };
            }
            """);
        try (Parser parser = new Parser(runtime, spin)) {
            long start = System.nanoTime();
            ParsedEvent parsed = parser.parse("spin");
            assertEquals("timeout", parsed.error());
            assertEquals("spin", parsed.message());
            assertTrue(System.nanoTime() - start < 2_000_000_000L);
            assertEquals("INFO", parser.parse("next").fields().get("level"));
        }
    }

    @Test
    void scriptErrorsAreReported() throws Exception {
        ParserScript failing = ParserScript.load(runtime, "failing", """
            export const fields = { n: { type: "int" } };
            export function parse(text) { return { n: text.foo.bar }; }
            """);
        try (Parser parser = new Parser(runtime, failing)) {
            ParsedEvent parsed = parser.parse("x");
            assertNotNull(parsed.error());
            assertEquals("x", parsed.message());
        }
        ParserScript badTime = ParserScript.load(runtime, "badtime", """
            export const fields = {};
            export function parse(text) { return { ts: text }; }
            """);
        try (Parser parser = new Parser(runtime, badTime)) {
            ParsedEvent parsed = parser.parse("garbage");
            assertTrue(parsed.error().startsWith("invalid result"), parsed.error());
        }
        assertThrows(ScriptException.class, () -> ParserScript.load(runtime, "broken", "export const fields = {"));
        assertThrows(ScriptException.class,
            () -> ParserScript.load(runtime, "nofields", "export function parse(t) { return {}; }"));
        assertThrows(ScriptException.class, () -> ParserScript.load(runtime, "reserved",
            "export const fields = { id: { type: 'string' } }; export function parse(t) { return {}; }"));
    }

    @Test
    void sandboxBlocksJava() throws Exception {
        ParserScript evil = ParserScript.load(runtime, "evil", """
            export const fields = { x: { type: "string" } };
            export function parse(text) { return { x: String(Java.type("java.lang.System")) }; }
            """);
        try (Parser parser = new Parser(runtime, evil)) {
            assertNotNull(parser.parse("x").error());
        }
    }
}
