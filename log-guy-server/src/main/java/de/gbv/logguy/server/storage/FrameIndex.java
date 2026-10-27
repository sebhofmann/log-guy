package de.gbv.logguy.server.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reads the {@code .idx} of a file in {@code hot/} or {@code archive/} after a start and repairs it.
 * <p>
 * Every line is one frame, and every frame starts where the previous one ends. A last line without {@code \n} is
 * what a crash or a failed write leaves behind and is cut off. A broken line in the middle is an error: it is
 * reported, and the frames behind it are kept as long as their offsets continue the frames before. If the broken
 * line is the beginning of a failed write directly followed by a complete line, the frame at its end is kept too.
 * The first frame that does not continue ends the index, it and everything behind it is dropped.
 */
public final class FrameIndex {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String TMP_SUFFIX = ".tmp";

    private FrameIndex() {
    }

    /**
     * How the frames of an index are read, written and chained.
     *
     * @param <T> the frame type
     */
    public interface Format<T> {

        /**
         * Parses one line.
         *
         * @param line the line without {@code \n}
         * @return the frame
         * @throws RuntimeException if the line is not a frame
         */
        T parse(String line);

        /**
         * Writes one frame as a line.
         *
         * @param frame the frame
         * @return the line without {@code \n}
         */
        String format(T frame);

        /**
         * Whether a frame directly continues another one.
         *
         * @param previous the frame before, {@code null} for the first frame
         * @param next the frame
         * @return whether all offsets of {@code next} start where {@code previous} ends
         */
        boolean follows(T previous, T next);
    }

    /**
     * The frames of an index after the repair.
     *
     * @param frames the frames
     * @param size size of the {@code .idx} after the repair
     * @param problems what was wrong with the index beyond an incomplete last line, empty if nothing
     * @param <T> the frame type
     */
    public record Recovered<T>(List<T> frames, long size, List<String> problems) {
    }

    /**
     * Reads an index and repairs it on disk: an incomplete last line or dropped frames are cut off, an index with
     * broken lines in the middle is written again atomically.
     *
     * @param idx the {@code .idx}, it may not exist
     * @param format how frames are read and written
     * @param opener opens the channel for writing the index again
     * @param <T> the frame type
     * @return the frames
     * @throws IOException if the index cannot be read or repaired
     */
    public static <T> Recovered<T> recover(Path idx, Format<T> format, FileChannelOpener opener) throws IOException {
        if (!Files.exists(idx)) {
            return new Recovered<>(List.of(), 0, List.of());
        }
        byte[] content = Files.readAllBytes(idx);
        List<T> frames = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        // end of the lines kept unchanged as long as nothing has to be written again
        int valid = 0;
        boolean rewrite = false;
        boolean stopped = false;
        int start = 0;
        int number = 0;
        for (int i = 0; i < content.length && !stopped; i++) {
            if (content[i] != '\n') {
                continue;
            }
            number++;
            String line = new String(content, start, i - start, StandardCharsets.UTF_8);
            T previous = frames.isEmpty() ? null : frames.getLast();
            T frame = parse(format, line);
            if (frame != null && format.follows(previous, frame)) {
                frames.add(frame);
                if (!rewrite) {
                    valid = i + 1;
                }
            } else if (frame != null) {
                problem(problems, idx, "line " + number + " at byte " + start + " does not continue frame "
                    + frames.size() + ", dropping it and the " + (content.length - start) + " bytes behind it");
                stopped = true;
            } else {
                T salvaged = salvage(format, line, previous);
                rewrite = true;
                if (salvaged != null) {
                    frames.add(salvaged);
                    problem(problems, idx, "line " + number + " at byte " + start
                        + " starts with the rest of a failed write, keeping the frame at its end");
                } else {
                    problem(problems, idx, "line " + number + " at byte " + start + " is broken, skipping it");
                }
            }
            start = i + 1;
        }
        if (!stopped && start < content.length) {
            LOGGER.warn("{}: cutting incomplete last line of {} bytes", idx, content.length - start);
        }
        long size;
        if (rewrite) {
            size = write(idx, frames, format, opener);
            LOGGER.error("{}: wrote the index again with {} frames, {} bytes before, {} after", idx, frames.size(),
                content.length, size);
        } else {
            size = valid;
            if (valid < content.length) {
                LOGGER.warn("{}: truncating index from {} to {} bytes", idx, content.length, valid);
                try (FileChannel channel = opener.open(idx, StandardOpenOption.WRITE)) {
                    channel.truncate(valid);
                    channel.force(false);
                }
            }
        }
        return new Recovered<>(frames, size, List.copyOf(problems));
    }

    private static <T> T parse(Format<T> format, String line) {
        try {
            return format.parse(line);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Finds a complete frame at the end of a line that starts with the rest of a failed write.
     */
    private static <T> T salvage(Format<T> format, String line, T previous) {
        for (int at = line.indexOf('{', 1); at > 0; at = line.indexOf('{', at + 1)) {
            T frame = parse(format, line.substring(at));
            if (frame != null && format.follows(previous, frame)) {
                return frame;
            }
        }
        return null;
    }

    private static void problem(List<String> problems, Path idx, String message) {
        LOGGER.error("{}: {}", idx, message);
        problems.add(message);
    }

    /**
     * Writes an index anew from the frames, atomically through a temporary file.
     *
     * @param idx the {@code .idx}
     * @param frames the frames
     * @param format how frames are written
     * @param opener opens the channel of the temporary file
     * @param <T> the frame type
     * @return size of the new index
     * @throws IOException if writing fails, the old index is then unchanged
     */
    public static <T> long write(Path idx, List<T> frames, Format<T> format, FileChannelOpener opener)
        throws IOException {
        StringBuilder lines = new StringBuilder();
        for (T frame : frames) {
            lines.append(format.format(frame)).append('\n');
        }
        byte[] bytes = lines.toString().getBytes(StandardCharsets.UTF_8);
        Path tmp = idx.resolveSibling(idx.getFileName() + TMP_SUFFIX);
        try (FileChannel channel = opener.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        }
        Files.move(tmp, idx, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return bytes.length;
    }
}
