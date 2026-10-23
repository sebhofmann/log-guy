package de.gbv.logguy.server.index;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.util.function.Predicate;

/**
 * Cuts the bytes of a log file into events. Lines are separated by {@code \n}, a parser decides with
 * {@code isStart} which line starts a new event. An event is complete when the next one starts or the file ends.
 */
public final class EventReader {

    private final int maxEventSize;

    private byte[] partial = new byte[0];

    private int partialLength;

    private long partialOffset;

    private long eventOffset = -1;

    private final List<String> eventLines = new ArrayList<>();

    private int eventSize;

    private boolean eventCut;

    /**
     * Creates a reader.
     *
     * @param offset offset in the file of the first byte that will be fed, must be the start of an event
     * @param maxEventSize events with more characters are cut
     */
    public EventReader(long offset, int maxEventSize) {
        this.partialOffset = offset;
        this.maxEventSize = maxEventSize;
    }

    /**
     * Feeds the next bytes and returns the events completed by them.
     *
     * @param data bytes following the ones fed before
     * @param isStart decides whether a line starts a new event, usually the parser script
     * @return complete events, in file order
     */
    public List<RawEvent> feed(byte[] data, Predicate<String> isStart) {
        List<RawEvent> events = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] != '\n') {
                continue;
            }
            long lineOffset = partialOffset;
            int lineBytes = partialLength + i - start;
            String line;
            if (partialLength > 0) {
                byte[] joined = Arrays.copyOf(partial, lineBytes);
                System.arraycopy(data, start, joined, partialLength, i - start);
                line = decode(joined, 0, lineBytes);
                partialLength = 0;
            } else {
                line = decode(data, start, lineBytes);
            }
            line(line, lineOffset, isStart, events);
            partialOffset = lineOffset + lineBytes + 1;
            start = i + 1;
        }
        if (start < data.length) {
            int rest = data.length - start;
            if (partial.length < partialLength + rest) {
                partial = Arrays.copyOf(partial, Math.max(partialLength + rest, partial.length * 2));
            }
            System.arraycopy(data, start, partial, partialLength, rest);
            partialLength += rest;
            // a line without end that grows beyond the limit, e.g. binary data, is split
            while (partialLength > maxEventSize) {
                line(decode(partial, 0, maxEventSize), partialOffset, isStart, events);
                System.arraycopy(partial, maxEventSize, partial, 0, partialLength - maxEventSize);
                partialLength -= maxEventSize;
                partialOffset += maxEventSize;
            }
        }
        return events;
    }

    private static String decode(byte[] bytes, int from, int length) {
        int end = from + length;
        if (end > from && bytes[end - 1] == '\r') {
            end--;
        }
        return new String(bytes, from, end - from, StandardCharsets.UTF_8);
    }

    private void line(String line, long offset, Predicate<String> isStart, List<RawEvent> events) {
        if (eventOffset >= 0 && isStart.test(line)) {
            events.add(finishEvent());
        }
        if (eventOffset < 0) {
            eventOffset = offset;
        }
        if (eventSize + line.length() <= maxEventSize) {
            eventLines.add(line);
            eventSize += line.length() + 1;
        } else if (!eventCut) {
            eventLines.add(line.substring(0, Math.max(0, maxEventSize - eventSize)));
            eventSize = maxEventSize;
            eventCut = true;
        }
    }

    private RawEvent finishEvent() {
        RawEvent event = new RawEvent(eventOffset, String.join("\n", eventLines));
        eventOffset = -1;
        eventLines.clear();
        eventSize = 0;
        eventCut = false;
        return event;
    }

    /**
     * Returns the pending events without consuming them: the open event and a last line without line break, which may
     * start an event of its own. Used for the end of a closed file and for provisional events of a growing stack
     * trace.
     *
     * @param isStart decides whether the last line starts a new event
     * @return the pending events, empty if nothing is pending
     */
    public List<RawEvent> tail(Predicate<String> isStart) {
        List<RawEvent> events = new ArrayList<>();
        String last = partialLength > 0 ? decode(partial, 0, partialLength) : null;
        if (eventOffset >= 0) {
            if (last != null && isStart.test(last)) {
                events.add(new RawEvent(eventOffset, String.join("\n", eventLines)));
                events.add(new RawEvent(partialOffset, last));
            } else {
                List<String> lines = new ArrayList<>(eventLines);
                if (last != null && eventSize < maxEventSize) {
                    lines.add(last.substring(0, Math.min(last.length(), maxEventSize - eventSize)));
                }
                events.add(new RawEvent(eventOffset, String.join("\n", lines)));
            }
        } else if (last != null) {
            events.add(new RawEvent(partialOffset, last));
        }
        return events;
    }

    /**
     * Whether an event or a line without line break is pending.
     *
     * @return true if {@link #tail(Predicate)} would return events
     */
    public boolean hasPending() {
        return eventOffset >= 0 || partialLength > 0;
    }

    /**
     * Offset of the first byte that does not belong to a returned event. Indexing continues there after a restart.
     *
     * @return the offset of the pending event, or of the next byte if nothing is pending
     */
    public long resumeOffset() {
        return eventOffset >= 0 ? eventOffset : partialOffset;
    }

    /**
     * Offset of the next byte to feed.
     *
     * @return the end of all bytes fed so far
     */
    public long readOffset() {
        return partialOffset + partialLength;
    }

    /**
     * An event as it is in the file.
     *
     * @param offset byte offset of the first line
     * @param text the lines, joined with {@code \n}
     */
    public record RawEvent(long offset, String text) {
    }
}
