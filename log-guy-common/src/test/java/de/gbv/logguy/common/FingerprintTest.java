package de.gbv.logguy.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class FingerprintTest {

    @Test
    void onlyPrefixCounts() {
        byte[] a = new byte[4096];
        byte[] b = new byte[4096];
        Arrays.fill(a, (byte) 'x');
        Arrays.fill(b, (byte) 'x');
        b[2000] = 'y';
        assertEquals(Fingerprint.of(a), Fingerprint.of(b));
        b[1023] = 'y';
        assertNotEquals(Fingerprint.of(a), Fingerprint.of(b));
    }

    @Test
    void shortFileHashesEverything() throws IOException {
        byte[] data = "2026-10-23T10:00:00,000 INFO  Foo: bar\n".getBytes();
        Fingerprint.Prefix prefix = Fingerprint.read(new ByteArrayInputStream(data));
        assertFalse(prefix.complete());
        assertEquals(Fingerprint.of(data), prefix.fingerprint());
    }

    @Test
    void format() throws IOException {
        Fingerprint.Prefix prefix = Fingerprint.read(new ByteArrayInputStream(new byte[2000]));
        assertTrue(prefix.complete());
        assertTrue(Fingerprint.isValid(prefix.fingerprint()));
        assertEquals(32, prefix.fingerprint().length());
        assertFalse(Fingerprint.isValid("../etc/passwd"));
        assertFalse(Fingerprint.isValid(prefix.fingerprint().toUpperCase()));
    }
}
