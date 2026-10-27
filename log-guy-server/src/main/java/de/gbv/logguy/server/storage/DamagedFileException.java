package de.gbv.logguy.server.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A file on disk no longer ends where its valid data end, and cutting it back failed or is not possible. Until the
 * file is repaired nothing may be appended to it.
 */
public class DamagedFileException extends IOException {

    private static final long serialVersionUID = 1L;

    private final transient Path path;

    /**
     * Creates the exception.
     *
     * @param path the damaged file
     * @param message what is wrong
     * @param cause the failed write, may be {@code null}
     */
    public DamagedFileException(Path path, String message, Throwable cause) {
        super(path + ": " + message, cause);
        this.path = path;
    }

    /**
     * The damaged file.
     *
     * @return its path
     */
    public Path getPath() {
        return path;
    }
}
