package de.gbv.logguy.server.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class FrameIndexTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    record F(long zst, int zlen, long raw, int rlen) {
    }

    private static final FrameIndex.Format<F> FORMAT = new FrameIndex.Format<>() {

        @Override
        public F parse(String line) {
            return JSON.readValue(line, F.class);
        }

        @Override
        public String format(F frame) {
            return JSON.writeValueAsString(frame);
        }

        @Override
        public boolean follows(F previous, F next) {
            long zst = previous == null ? 0 : previous.zst() + previous.zlen();
            long raw = previous == null ? 0 : previous.raw() + previous.rlen();
            return next.zst() == zst && next.raw() == raw;
        }
    };

    private static final List<F> FRAMES = List.of(new F(0, 10, 0, 100), new F(10, 12, 100, 90),
        new F(22, 8, 190, 50), new F(30, 9, 240, 70));

    @TempDir
    Path dir;

    private static String line(F frame) {
        return FORMAT.format(frame) + "\n";
    }

    private Path write(String content) throws Exception {
        Path idx = dir.resolve("a.idx");
        Files.writeString(idx, content, StandardCharsets.UTF_8);
        return idx;
    }

    @Test
    void incompleteLastLineIsCutWithoutProblem() throws Exception {
        String valid = line(FRAMES.get(0)) + line(FRAMES.get(1));
        Path idx = write(valid + "{\"zst\":22,\"zl");
        FrameIndex.Recovered<F> result = FrameIndex.recover(idx, FORMAT, FileChannelOpener.DEFAULT);
        assertEquals(FRAMES.subList(0, 2), result.frames());
        assertEquals(List.of(), result.problems());
        assertEquals(valid, Files.readString(idx));
        assertEquals(valid.length(), result.size());
    }

    @Test
    void frameBehindTheRestOfAFailedWriteIsKept() throws Exception {
        // a failed write left half a line, the next write appended its complete line directly behind it
        String broken = line(FRAMES.get(0)) + "{\"zst\":10,\"zlen\":1" + line(FRAMES.get(1)) + line(FRAMES.get(2));
        Path idx = write(broken);
        FrameIndex.Recovered<F> result = FrameIndex.recover(idx, FORMAT, FileChannelOpener.DEFAULT);
        assertEquals(FRAMES.subList(0, 3), result.frames());
        assertEquals(1, result.problems().size(), result.problems().toString());
        String repaired = line(FRAMES.get(0)) + line(FRAMES.get(1)) + line(FRAMES.get(2));
        assertEquals(repaired, Files.readString(idx));
        assertEquals(repaired.length(), result.size());
        // repaired for good
        assertEquals(List.of(), FrameIndex.recover(idx, FORMAT, FileChannelOpener.DEFAULT).problems());
    }

    @Test
    void brokenLineInTheMiddleIsSkippedIfTheNextFramesContinue() throws Exception {
        Path idx = write(line(FRAMES.get(0)) + "garbage\n" + line(FRAMES.get(1)) + line(FRAMES.get(2))
            + line(FRAMES.get(3)));
        FrameIndex.Recovered<F> result = FrameIndex.recover(idx, FORMAT, FileChannelOpener.DEFAULT);
        assertEquals(FRAMES, result.frames());
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().getFirst().contains("line 2"), result.problems().toString());
    }

    @Test
    void framesBehindAGapAreDroppedLoudly() throws Exception {
        // the broken line was the only record of frame 1, frames 2 and 3 cannot be placed
        Path idx = write(line(FRAMES.get(0)) + "{\"zst\":10,\"zlen\":12,\"raw\":10\n" + line(FRAMES.get(2))
            + line(FRAMES.get(3)));
        FrameIndex.Recovered<F> result = FrameIndex.recover(idx, FORMAT, FileChannelOpener.DEFAULT);
        assertEquals(FRAMES.subList(0, 1), result.frames());
        assertEquals(2, result.problems().size(), result.problems().toString());
        assertEquals(line(FRAMES.get(0)), Files.readString(idx));
    }

    @Test
    void frameThatDoesNotContinueEndsTheIndex() throws Exception {
        String valid = line(FRAMES.get(0)) + line(FRAMES.get(1));
        Path idx = write(valid + line(FRAMES.get(3)));
        FrameIndex.Recovered<F> result = FrameIndex.recover(idx, FORMAT, FileChannelOpener.DEFAULT);
        assertEquals(FRAMES.subList(0, 2), result.frames());
        assertEquals(1, result.problems().size());
        assertEquals(valid, Files.readString(idx));
    }
}
