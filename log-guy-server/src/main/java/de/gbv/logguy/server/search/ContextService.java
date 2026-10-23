package de.gbv.logguy.server.search;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

import org.springframework.security.access.AccessDeniedException;

import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.Right;
import de.gbv.logguy.server.config.LogGuyProperties.SearchProperties;
import de.gbv.logguy.server.hot.FileMeta;
import de.gbv.logguy.server.hot.HotFile;
import de.gbv.logguy.server.hot.HotStore;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * Reads the original lines around an event directly from {@code hot/}, by the byte offset in the event id.
 */
public class ContextService {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    private final SearchProperties settings;

    private final HotStore store;

    /**
     * Creates the service.
     *
     * @param properties configuration
     * @param store the hot storage
     */
    public ContextService(LogGuyProperties properties, HotStore store) {
        this.settings = properties.search();
        this.store = store;
    }

    /**
     * Reads the lines before an event and the lines starting with it.
     *
     * @param user the user, needs {@link Right#READ} for the application of the file
     * @param id the event id {@code <fingerprint>:<offset>}
     * @param before lines before the event, {@code null} for the default
     * @param after lines from the start of the event on, {@code null} for the default
     * @return the lines, without ANSI colors
     * @throws IOException if reading fails
     * @throws NoSuchElementException if the file is not in {@code hot/}
     * @throws AccessDeniedException if the user may not read the application
     */
    public ContextView context(LogGuyUser user, String id, Integer before, Integer after) throws IOException {
        int separator = id.lastIndexOf(':');
        long offset;
        try {
            offset = Long.parseLong(id.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw new SearchException("Ungültige Event-ID „" + id + "“.");
        }
        if (separator <= 0 || offset < 0) {
            throw new SearchException("Ungültige Event-ID „" + id + "“.");
        }
        HotFile file = store.find(id.substring(0, separator))
            .orElseThrow(() -> new NoSuchElementException("Die Datei des Events liegt nicht mehr in hot/."));
        FileMeta meta = file.meta();
        if (!user.may(Right.READ, meta.app())) {
            throw new AccessDeniedException("app " + meta.app());
        }
        int linesBefore = before == null ? settings.contextLines() : Math.clamp(before, 0, settings.maxContextLines());
        int linesAfter = after == null ? settings.contextLines() : Math.clamp(after, 1, settings.maxContextLines());
        long window = settings.contextBytes().toBytes();
        long length = file.length();
        if (offset > length) {
            throw new NoSuchElementException("Das Event liegt hinter dem Ende der Datei.");
        }

        long start = Math.max(0, offset - window);
        List<String> head = lines(read(file, start, offset));
        if (start > 0 && !head.isEmpty()) {
            head.removeFirst();
        }
        List<String> previous = new ArrayList<>(head.subList(Math.max(0, head.size() - linesBefore), head.size()));

        long end = Math.min(length, offset + window);
        List<String> tail = lines(read(file, offset, end));
        boolean complete = end == length;
        if (!complete && tail.size() > 1) {
            tail.removeLast();
        }
        List<String> lines = new ArrayList<>(tail.subList(0, Math.min(tail.size(), linesAfter)));
        boolean atStart = start == 0 && previous.size() == head.size();
        boolean atEnd = complete && lines.size() == tail.size();
        return new ContextView(id, meta.app(), meta.host(), previous, lines, atStart, atEnd);
    }

    private static byte[] read(HotFile file, long from, long to) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) (to - from));
        long position = from;
        while (position < to) {
            byte[] data = file.read(position, (int) (to - position));
            if (data.length == 0) {
                break;
            }
            out.write(data);
            position += data.length;
        }
        return out.toByteArray();
    }

    private static List<String> lines(byte[] data) {
        if (data.length == 0) {
            return new ArrayList<>();
        }
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        return new ArrayList<>(Arrays.stream(text.split("\r?\n", -1))
            .map(line -> ANSI.matcher(line).replaceAll(""))
            .toList());
    }

    /**
     * Lines around an event.
     *
     * @param id the event id
     * @param app the application
     * @param host the host
     * @param before lines before the event
     * @param lines lines from the start of the event on
     * @param start whether {@code before} reaches the start of the file
     * @param end whether {@code lines} reaches the current end of the file
     */
    public record ContextView(String id, String app, String host, List<String> before, List<String> lines,
        boolean start, boolean end) {
    }
}
