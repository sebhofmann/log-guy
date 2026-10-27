package de.gbv.logguy.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.config.LogGuyProperties.TokenProperties;

/**
 * Looks up access tokens by their hash. Only hashes are configured, a token is shown once when it is created.
 */
public class TokenRegistry {

    /** Prefix of a configured hash. */
    public static final String HASH_PREFIX = "sha256:";

    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AtomicReference<Map<String, TokenProperties>> byHash = new AtomicReference<>(Map.of());

    /**
     * Creates the registry.
     *
     * @param tokens the configured tokens
     */
    public TokenRegistry(List<TokenProperties> tokens) {
        update(tokens);
    }

    /**
     * Replaces all tokens, e.g. after a reload.
     *
     * @param tokens the configured tokens
     */
    public final void update(List<TokenProperties> tokens) {
        Map<String, TokenProperties> map = new HashMap<>();
        for (TokenProperties token : tokens) {
            map.put(token.hash().toLowerCase(Locale.ROOT), token);
        }
        byHash.set(Map.copyOf(map));
    }

    /**
     * Looks up a token.
     *
     * @param token the token as sent by the client
     * @return the configured token, if any
     */
    public Optional<TokenProperties> find(String token) {
        return Optional.ofNullable(byHash.get().get(hash(token)));
    }

    /**
     * Looks up a token by its name, e.g. to check that a token used for a long running request is still configured.
     *
     * @param name the name of the token
     * @return the configured token, if any
     */
    public Optional<TokenProperties> findByName(String name) {
        return byHash.get().values().stream().filter(token -> token.name().equals(name)).findFirst();
    }

    /**
     * Hashes a token the way it is configured.
     *
     * @param token the token
     * @return {@code sha256:<hex>}
     */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HASH_PREFIX + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Creates a new random token.
     *
     * @param kind the kind, becomes part of the prefix so tokens can be told apart
     * @return the token, e.g. {@code lg_ingest_...}
     */
    public static String generate(TokenKind kind) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return "lg_" + kind.name().toLowerCase(Locale.ROOT) + "_"
            + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
