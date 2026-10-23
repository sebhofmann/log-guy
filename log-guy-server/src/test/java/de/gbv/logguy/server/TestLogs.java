package de.gbv.logguy.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Real log lines for tests. The sample is the start of a production day of digibib with shortened IP addresses.
 */
public final class TestLogs {

    private TestLogs() {
    }

    /**
     * The MyCoRe sample with 2000 lines, ANSI colors and stack traces.
     *
     * @return the bytes of the sample
     */
    public static byte[] mycoreSample() {
        try (InputStream in = TestLogs.class.getResourceAsStream("/logs/mycore-app-sample.log")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
