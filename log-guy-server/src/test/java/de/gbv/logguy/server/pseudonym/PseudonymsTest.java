package de.gbv.logguy.server.pseudonym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class PseudonymsTest {

    @Test
    void sameValueGetsSamePseudonymPerSession() {
        Pseudonyms session = new Pseudonyms();
        assertEquals("$Nutzer1", session.pseudonym("Nutzer", "mueller"));
        assertEquals("$Nutzer2", session.pseudonym("Nutzer", "schulz"));
        assertEquals("$Nutzer1", session.pseudonym("Nutzer", "mueller"));
        assertEquals("$IP1", session.pseudonym("IP", "10.0.0.1"));
        assertEquals("$Nutzer1", session.pseudonym("Nutzer", "$Nutzer1"));
        assertEquals("", session.pseudonym("Nutzer", ""));
        assertEquals(Map.of("Nutzer", 2, "IP", 1), session.counts());
        assertEquals("$Nutzer1", new Pseudonyms().pseudonym("Nutzer", "schulz"));
        assertThrows(IllegalArgumentException.class, () -> session.pseudonym("a b", "x"));
        assertThrows(IllegalArgumentException.class, () -> session.pseudonym("1", "x"));
    }

    @Test
    void resolvesPseudonymsInQueries() {
        Pseudonyms session = new Pseudonyms();
        session.pseudonym("Nutzer", "mueller");
        session.pseudonym("Nutzer", "o'brien");
        session.pseudonym("IP", "10.0.0.1");
        assertEquals("login:\"mueller\" AND message:\"10.0.0.1\" OR login:\"o'brien\" $Nutzer3 $Nutzer10",
            session.resolve("login:$Nutzer1 AND message:$IP1 OR login:$Nutzer2 $Nutzer3 $Nutzer10",
                value -> "\"" + value + "\""));
        assertEquals("$Nutzer1x", session.resolve("$Nutzer1x", value -> "X"));
        assertEquals(Optional.of("mueller"), session.real("$Nutzer1"));
        assertTrue(session.isPseudonym("$IP1"));
        assertFalse(session.isPseudonym("$IP2"));
        assertFalse(session.isPseudonym("mueller"));
    }
}
