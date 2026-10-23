package de.gbv.logguy.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.search.SearchException;

class McpQueriesTest {

    private static final Set<String> PROTECTED = Set.of("login", "ip");

    @Test
    void translatesPseudonymsBack() {
        Pseudonyms session = new Pseudonyms();
        session.pseudonym("Nutzer", "mueller");
        session.pseudonym("IP", "10.0.0.1");
        assertEquals("login:\"mueller\" AND level:ERROR",
            McpQueries.query("login:$Nutzer1 AND level:ERROR", PROTECTED, session));
        assertEquals("message:\"from 10.0.0.1 by mueller\"",
            McpQueries.query("message:\"from $IP1 by $Nutzer1\"", PROTECTED, session));
        assertEquals("ip:\"10.0.0.1\"", McpQueries.query("ip:\"$IP1\"", PROTECTED, session));
        assertEquals("$Nutzer7", McpQueries.query("$Nutzer7", PROTECTED, session));
        assertEquals(Map.of("login", List.of("mueller"), "level", List.of("ERROR")),
            McpQueries.filters(Map.of("login", List.of("$Nutzer1"), "level", List.of("ERROR")), PROTECTED, session));
    }

    @Test
    void rejectsClearValuesOnProtectedFields() {
        Pseudonyms session = new Pseudonyms();
        session.pseudonym("Nutzer", "mueller");
        assertThrows(SearchException.class, () -> McpQueries.query("login:mueller", PROTECTED, session));
        assertThrows(SearchException.class, () -> McpQueries.query("level:ERROR AND -login : mue*", PROTECTED,
            session));
        assertThrows(SearchException.class, () -> McpQueries.query("login:(a OR b)", PROTECTED, session));
        assertThrows(SearchException.class, () -> McpQueries.query("login:$Nutzer2", PROTECTED, session));
        assertThrows(SearchException.class, () -> McpQueries.query("login:$Nutzer1*", PROTECTED, session));
        assertThrows(SearchException.class,
            () -> McpQueries.filters(Map.of("login", List.of("mueller")), PROTECTED, session));
        assertEquals("message:login", McpQueries.query("message:login", PROTECTED, session));
    }
}
