package de.gbv.logguy.server.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import de.gbv.logguy.common.TestLogs;

class EventReaderTest {

    /** Lines with a time stamp start an event, as in the MyCoRe parser. */
    private static final Predicate<String> IS_START = line -> line.replaceAll("\u001b\\[[0-9;]*m", "")
        .matches("^\\d{4}-\\d\\d-\\d\\dT.*");

    private final byte[] sample = TestLogs.mycoreSample();

    private static List<EventReader.RawEvent> readAll(EventReader reader, byte[] data, int[] cuts) {
        List<EventReader.RawEvent> events = new ArrayList<>();
        int from = 0;
        for (int cut : cuts) {
            events.addAll(reader.feed(Arrays.copyOfRange(data, from, cut), IS_START));
            from = cut;
        }
        events.addAll(reader.feed(Arrays.copyOfRange(data, from, data.length), IS_START));
        events.addAll(reader.tail(IS_START));
        return events;
    }

    @Test
    void chunkingDoesNotMatter() {
        List<EventReader.RawEvent> whole = readAll(new EventReader(0, 1 << 20), sample, new int[0]);
        Random random = new Random(7);
        int[] cuts = random.ints(200, 0, sample.length).sorted().toArray();
        List<EventReader.RawEvent> chunked = readAll(new EventReader(0, 1 << 20), sample, cuts);
        assertEquals(whole, chunked);
        assertTrue(whole.size() > 1000);
    }

    @Test
    void offsetsPointToTheEventInTheFile() {
        for (EventReader.RawEvent event : readAll(new EventReader(0, 1 << 20), sample, new int[0])) {
            byte[] first = event.text().split("\n", 2)[0].getBytes(StandardCharsets.UTF_8);
            assertEquals(new String(first, StandardCharsets.UTF_8), new String(sample, (int) event.offset(),
                first.length, StandardCharsets.UTF_8));
        }
    }

    @Test
    void lastLineWithoutLineBreak() {
        EventReader reader = new EventReader(0, 1 << 20);
        String text = "2026-10-01T00:00:00,000 ERROR x: boom\n\tat a.B.c(B.java:1)\n2026-10-01T00:00:01,000 INFO";
        // whether the last line starts an event is only known once it is complete
        assertTrue(reader.feed(text.getBytes(StandardCharsets.UTF_8), IS_START).isEmpty());
        assertEquals(0, reader.resumeOffset());
        List<EventReader.RawEvent> tail = reader.tail(IS_START);
        assertEquals(List.of(new EventReader.RawEvent(0, "2026-10-01T00:00:00,000 ERROR x: boom\n\tat a.B.c(B.java:1)"),
            new EventReader.RawEvent(58, "2026-10-01T00:00:01,000 INFO")), tail);
        // tail does not consume anything
        assertEquals(tail, reader.tail(IS_START));
        List<EventReader.RawEvent> done = reader.feed(" y: done\n".getBytes(StandardCharsets.UTF_8), IS_START);
        assertEquals(List.of(tail.get(0)), done);
        assertEquals(58, reader.resumeOffset());
        assertEquals(List.of(new EventReader.RawEvent(58, "2026-10-01T00:00:01,000 INFO y: done")),
            reader.tail(IS_START));
    }

    @Test
    void longEventsAreCut() {
        EventReader reader = new EventReader(0, 100);
        String line = "2026-10-01T00:00:00,000 ERROR x: " + "y".repeat(500) + "\n";
        List<EventReader.RawEvent> events = reader.feed((line + line).getBytes(StandardCharsets.UTF_8), IS_START);
        assertEquals(1, events.size());
        assertEquals(100, events.get(0).text().length());
        assertEquals(line.length(), reader.resumeOffset());
        assertTrue(new EventReader(0, 100).tail(IS_START).isEmpty());
    }
}
