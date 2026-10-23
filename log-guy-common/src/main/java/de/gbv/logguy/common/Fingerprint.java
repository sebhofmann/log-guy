package de.gbv.logguy.common;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Identifies a log file by its content instead of its name.
 * <p>
 * The fingerprint is the SHA-256 hash over the first {@value #PREFIX_BYTES} bytes of the uncompressed content,
 * truncated to {@value #HASH_BYTES} bytes and written as lower case hex. A live file and its rotated, compressed
 * copy therefore share the same fingerprint. A file shorter than {@value #PREFIX_BYTES} bytes is hashed over its
 * whole content, so its fingerprint is only final once the file is complete.
 */
public final class Fingerprint {

    /** Number of leading bytes the fingerprint is computed from. Part of the protocol, never configurable. */
    public static final int PREFIX_BYTES = 1024;

    /** Number of hash bytes kept. */
    public static final int HASH_BYTES = 16;

    private static final Pattern VALID = Pattern.compile("[0-9a-f]{" + (HASH_BYTES * 2) + "}");

    private Fingerprint() {
    }

    /**
     * Computes the fingerprint over the first {@value #PREFIX_BYTES} bytes of the given data, or over all of it if
     * it is shorter.
     *
     * @param data the uncompressed content, starting at offset 0 of the file
     * @param length number of valid bytes in {@code data}
     * @return the fingerprint as hex string
     */
    public static String of(byte[] data, int length) {
        MessageDigest digest = sha256();
        digest.update(data, 0, Math.min(length, PREFIX_BYTES));
        byte[] hash = digest.digest();
        return HexFormat.of().formatHex(hash, 0, HASH_BYTES);
    }

    /**
     * Computes the fingerprint over all given bytes, see {@link #of(byte[], int)}.
     *
     * @param data the uncompressed content, starting at offset 0 of the file
     * @return the fingerprint as hex string
     */
    public static String of(byte[] data) {
        return of(data, data.length);
    }

    /**
     * Reads the prefix of a stream and computes the fingerprint. The stream is not closed.
     *
     * @param in uncompressed content, positioned at offset 0 of the file
     * @return the fingerprint and whether the prefix was complete
     * @throws IOException if reading fails
     */
    public static Prefix read(InputStream in) throws IOException {
        byte[] prefix = in.readNBytes(PREFIX_BYTES);
        return new Prefix(of(prefix), prefix.length == PREFIX_BYTES);
    }

    /**
     * Checks whether a string is a well formed fingerprint.
     *
     * @param fingerprint the candidate
     * @return true if it consists of the expected number of lower case hex digits
     */
    public static boolean isValid(String fingerprint) {
        return fingerprint != null && VALID.matcher(fingerprint).matches();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Result of reading the prefix of a stream.
     *
     * @param fingerprint the fingerprint over the bytes read
     * @param complete true if {@value Fingerprint#PREFIX_BYTES} bytes were available, so the fingerprint is final
     *     even if the file keeps growing
     */
    public record Prefix(String fingerprint, boolean complete) {
    }
}
