package de.gbv.logguy.server.pseudonym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ArchiveTokensTest {

    @Test
    void sameValueSameTokenWithinAFileOnly() {
        String key = ArchiveTokens.newKey();
        assertEquals(32, Base64.getDecoder().decode(key).length);
        ArchiveTokens file = new ArchiveTokens(key, Set.of());
        String token = file.token("Nutzer", "mueller");
        assertTrue(token.matches("\\[Nutzer#[0-9a-f]{8}]"), token);
        assertEquals(token, file.token("Nutzer", "mueller"));
        assertEquals(token, new ArchiveTokens(key, Set.of()).token("Nutzer", "mueller"));
        assertNotEquals(token, file.token("Nutzer", "schulze"));
        // the category is part of the hash
        assertNotEquals(token.substring(token.indexOf('#')), file.token("Mail", "mueller")
            .substring(file.token("Mail", "mueller").indexOf('#')));
        assertNotEquals(token, new ArchiveTokens(ArchiveTokens.newKey(), Set.of()).token("Nutzer", "mueller"));
    }

    @Test
    void keptCategoriesTokensAndEmptyValuesStay() {
        ArchiveTokens file = new ArchiveTokens(ArchiveTokens.newKey(), Set.of("IP"));
        assertEquals("192.0.2.0", file.token("IP", "192.0.2.0"));
        assertEquals("", file.token("Nutzer", ""));
        assertEquals("[Nutzer#0badc0de]", file.token("Nutzer", "[Nutzer#0badc0de]"));
        assertEquals("[Mail#0badc0de]", file.token("Nutzer", "[Mail#0badc0de]"));
        assertThrows(IllegalArgumentException.class, () -> file.token("Nutzer 1", "x"));
    }

    @Test
    void recognizesTokens() {
        assertTrue(ArchiveTokens.isToken("[Nutzer#3f9a1c2e]"));
        assertTrue(ArchiveTokens.isToken("[Kennung_X#00000000]"));
        assertFalse(ArchiveTokens.isToken("[Nutzer#3F9A1C2E]"));
        assertFalse(ArchiveTokens.isToken("[Nutzer#3f9a1c2]"));
        assertFalse(ArchiveTokens.isToken("x[Nutzer#3f9a1c2e]"));
        assertFalse(ArchiveTokens.isToken("$Nutzer1"));
        assertFalse(ArchiveTokens.isToken(null));
    }
}
