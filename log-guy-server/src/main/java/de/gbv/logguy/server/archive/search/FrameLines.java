package de.gbv.logguy.server.archive.search;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * The lines of a decompressed frame: the text without ANSI colors for matching and the byte offset of each line.
 * ANSI sequences never contain a line break, so line {@code i} of the text is line {@code i} of the bytes.
 */
final class FrameLines {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    private final String text;

    private final long[] byteStarts;

    private final int[] charStarts;

    private final int count;

    /**
     * Splits a frame.
     *
     * @param data the decompressed frame
     * @param raw offset of the frame in the filtered file
     */
    FrameLines(byte[] data, long raw) {
        String decoded = new String(data, StandardCharsets.UTF_8);
        this.text = decoded.indexOf('\u001B') < 0 ? decoded : ANSI.matcher(decoded).replaceAll("");
        int lines = 0;
        long[] bytes = new long[64];
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n' || i == data.length - 1) {
                if (lines == bytes.length) {
                    bytes = Arrays.copyOf(bytes, lines * 2);
                }
                bytes[lines++] = raw + start;
                start = i + 1;
            }
        }
        int[] chars = new int[lines + 1];
        int line = 0;
        int position = 0;
        while (line < lines) {
            chars[line++] = position;
            int end = text.indexOf('\n', position);
            position = end < 0 ? text.length() : end + 1;
        }
        chars[lines] = text.length();
        this.byteStarts = bytes;
        this.charStarts = chars;
        this.count = lines;
    }

    String text() {
        return text;
    }

    int count() {
        return count;
    }

    /**
     * A line without its line break.
     *
     * @param index index of the line
     * @return the text
     */
    String line(int index) {
        int start = charStarts[index];
        int end = charStarts[index + 1];
        if (end > start && text.charAt(end - 1) == '\n') {
            end--;
        }
        if (end > start && text.charAt(end - 1) == '\r') {
            end--;
        }
        return text.substring(start, end);
    }

    long offset(int index) {
        return byteStarts[index];
    }

    /**
     * The line containing a position of the text.
     *
     * @param position index in {@link #text()}
     * @return the index of the line
     */
    int lineAt(int position) {
        int found = Arrays.binarySearch(charStarts, 0, count, position);
        return found >= 0 ? found : -found - 2;
    }

    /**
     * Where the line after a line starts.
     *
     * @param index index of the line
     * @return the position in {@link #text()}
     */
    int nextStart(int index) {
        return charStarts[index + 1];
    }
}
