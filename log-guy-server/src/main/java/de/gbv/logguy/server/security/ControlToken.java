package de.gbv.logguy.server.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Token for {@code log-guy reload} on the same host. The server creates a new one at every start and writes it to
 * {@code state/control.token}, readable only for its own user. Whoever can read the file may trigger a reload.
 */
public class ControlToken {

    /** Name of the file in {@code state/}. */
    public static final String FILE_NAME = "control.token";

    private static final int BYTES = 32;

    private final byte[] token;

    private ControlToken(String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates a new token and writes it to {@code state/control.token}.
     *
     * @param dataDir the data directory
     * @return the token
     * @throws IOException if the file cannot be written
     */
    public static ControlToken create(Path dataDir) throws IOException {
        byte[] random = new byte[BYTES];
        new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Path file = file(dataDir);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.deleteIfExists(tmp);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(tmp, token);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new ControlToken(token);
    }

    /**
     * Reads the token the running server wrote.
     *
     * @param dataDir the data directory
     * @return the token
     * @throws IOException if the file cannot be read
     */
    public static String read(Path dataDir) throws IOException {
        return Files.readString(file(dataDir)).trim();
    }

    /**
     * The path of the token file.
     *
     * @param dataDir the data directory
     * @return {@code state/control.token}
     */
    public static Path file(Path dataDir) {
        return dataDir.resolve("state").resolve(FILE_NAME);
    }

    /**
     * Compares in constant time.
     *
     * @param candidate the token of a request
     * @return true if it is this token
     */
    public boolean matches(String candidate) {
        return candidate != null
            && MessageDigest.isEqual(token, candidate.trim().getBytes(StandardCharsets.UTF_8));
    }
}
