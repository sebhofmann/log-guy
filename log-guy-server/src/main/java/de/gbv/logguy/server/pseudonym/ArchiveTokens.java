package de.gbv.logguy.server.pseudonym;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The tokens that replace personal values in the archive, e.g. {@code [Nutzer#3f9a1c2e]}: the first 32 bits of
 * HMAC-SHA256 over category and value, with a key of the file. The same value gets the same token within a file,
 * across files nothing can be linked. Not thread safe, one instance belongs to one file being written.
 */
public final class ArchiveTokens {

    /** A token in the archive. */
    public static final Pattern TOKEN = Pattern.compile("\\[\\p{L}[\\p{L}_]*#[0-9a-f]{8}]");

    private static final Pattern CATEGORY = Pattern.compile("\\p{L}[\\p{L}_]*");

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int KEY_BYTES = 32;

    private static final int TOKEN_BYTES = 4;

    private final Mac mac;

    private final Set<String> keep;

    /**
     * Creates the tokens of a file.
     *
     * @param key the key of the file, Base64, see {@link #newKey()}
     * @param keep categories whose values stay as they are, e.g. {@code IP}
     */
    public ArchiveTokens(String key, Set<String> keep) {
        this.keep = Set.copyOf(keep);
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(key), "HmacSHA256"));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /**
     * Creates a new random key: 256 bits from {@link SecureRandom}.
     *
     * @return the key, Base64
     */
    public static String newKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * Whether a text is a token of the archive, so it is not pseudonymized again.
     *
     * @param text the text
     * @return true if it is a token
     */
    public static boolean isToken(String text) {
        return text != null && text.length() >= 12 && text.charAt(0) == '[' && TOKEN.matcher(text).matches();
    }

    /**
     * The token of a value.
     *
     * @param category e.g. {@code Nutzer}
     * @param value the real value
     * @return the token, or the value itself if it is empty, already a token or its category is kept
     * @throws IllegalArgumentException if the category is not made of letters
     */
    public String token(String category, String value) {
        if (category == null || !CATEGORY.matcher(category).matches()) {
            throw new IllegalArgumentException("invalid category " + category);
        }
        if (value == null || value.isEmpty() || keep.contains(category) || isToken(value)) {
            return value;
        }
        mac.update(category.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 0);
        byte[] hash = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return "[" + category + "#" + HexFormat.of().formatHex(hash, 0, TOKEN_BYTES) + "]";
    }
}
