package de.gbv.logguy.server.hot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.luben.zstd.Zstd;

import de.gbv.logguy.common.StreamStatus;
import de.gbv.logguy.server.config.LogGuyProperties.HotProperties;
import de.gbv.logguy.server.pseudonym.ArchiveTokens;
import de.gbv.logguy.server.storage.Appends;
import de.gbv.logguy.server.storage.DamagedFileException;
import de.gbv.logguy.server.storage.FileChannelOpener;
import de.gbv.logguy.server.storage.FrameIndex;
import tools.jackson.databind.json.JsonMapper;

/**
 * One log file in {@code hot/}, stored byte for byte as it came from the agent.
 * <p>
 * Four files belong together, all named {@code <host>/<app>/<YYYY-MM-DD>-<fingerprint>} plus suffix:
 * <ul>
 * <li>{@code .log.zst}: independent zstd frames of about {@code frame-size} uncompressed bytes, cut at line ends</li>
 * <li>{@code .idx}: one JSON line per frame, see {@link Frame}</li>
 * <li>{@code .tail}: bytes not yet in a frame, an 8 byte offset of the first byte followed by the bytes</li>
 * <li>{@code .meta.json}: see {@link FileMeta}</li>
 * </ul>
 * Bytes are synced to the {@code .tail} before {@link #append} returns. A frame is first synced to the
 * {@code .log.zst}, then to the {@code .idx}, then the {@code .tail} is replaced. After a crash in between, the
 * offset in the {@code .tail} shows which of its bytes are already in a frame.
 * <p>
 * Appends to {@code .tail}, {@code .log.zst} and {@code .idx} go through {@link Appends}: a write that fails, e.g. on
 * a full disk, is cut back, so a chunk sent again after an error is stored once. If cutting back fails as well, the
 * file is damaged: the {@code .tail} is written again from memory, and until that succeeds no bytes are appended.
 * The damage is listed in {@link #problems()} like the problems {@link FrameIndex} finds in the {@code .idx} on load.
 */
public final class HotFile {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String ZST_SUFFIX = ".log.zst";

    private static final String IDX_SUFFIX = ".idx";

    private static final String TAIL_SUFFIX = ".tail";

    /** Suffix of the meta file, used to find all files on startup. */
    static final String META_SUFFIX = ".meta.json";

    private static final String TMP_SUFFIX = ".tmp";

    private final Path zstPath;

    private final Path idxPath;

    private final Path tailPath;

    private final Path metaPath;

    private final String relativePath;

    private final HotProperties settings;

    private final JsonMapper json;

    private final FileChannelOpener opener;

    private final ReentrantLock lock = new ReentrantLock();

    // set by delete(), a writer still holding this instance must not create the files again
    private boolean deleted;

    private final List<Frame> frames = new ArrayList<>();

    private long tailBase;

    private byte[] tail = new byte[0];

    private int tailLength;

    private Instant tailSince;

    private FileMeta meta;

    private FileChannel tailChannel;

    // size of the valid lines in the .idx, the next line is written there
    private long idxSize;

    // the .tail on disk may differ from the memory, it is written again before the next append
    private boolean tailDirty;

    // problems found on load, they stay until the next start
    private final List<String> loadProblems = new ArrayList<>();

    // files with bytes behind their end that could not be cut off, by path
    private final Map<Path, String> damaged = new LinkedHashMap<>();

    private HotFile(Path dir, String name, String relativeDir, HotProperties settings, JsonMapper json,
        FileChannelOpener opener) {
        this.zstPath = dir.resolve(name + ZST_SUFFIX);
        this.idxPath = dir.resolve(name + IDX_SUFFIX);
        this.tailPath = dir.resolve(name + TAIL_SUFFIX);
        this.metaPath = dir.resolve(name + META_SUFFIX);
        this.relativePath = relativeDir + "/" + name + ZST_SUFFIX;
        this.settings = settings;
        this.json = json;
        this.opener = opener;
    }

    /**
     * Creates a new, empty file.
     *
     * @param root the {@code hot/} directory
     * @param fingerprint fingerprint of the file
     * @param host host the file comes from, already validated
     * @param app application, already validated
     * @param created day of the first byte
     * @param now time of the first byte
     * @param settings frame settings
     * @param json mapper for {@code .idx} and {@code .meta.json}
     * @return the new file
     * @throws IOException if the files cannot be written
     */
    static HotFile create(Path root, String fingerprint, String host, String app, LocalDate created, Instant now,
        HotProperties settings, JsonMapper json) throws IOException {
        return create(root, fingerprint, host, app, created, now, settings, json, FileChannelOpener.DEFAULT);
    }

    /**
     * Creates a new, empty file that writes through the given channels.
     *
     * @param root the {@code hot/} directory
     * @param fingerprint fingerprint of the file
     * @param host host the file comes from, already validated
     * @param app application, already validated
     * @param created day of the first byte
     * @param now time of the first byte
     * @param settings frame settings
     * @param json mapper for {@code .idx} and {@code .meta.json}
     * @param opener opens all channels the file writes through
     * @return the new file
     * @throws IOException if the files cannot be written
     */
    static HotFile create(Path root, String fingerprint, String host, String app, LocalDate created, Instant now,
        HotProperties settings, JsonMapper json, FileChannelOpener opener) throws IOException {
        String relativeDir = host + "/" + app;
        Path dir = root.resolve(relativeDir);
        Files.createDirectories(dir);
        HotFile file = new HotFile(dir, created + "-" + fingerprint, relativeDir, settings, json, opener);
        // left over by a crash during delete
        Files.deleteIfExists(file.zstPath);
        Files.deleteIfExists(file.idxPath);
        Files.deleteIfExists(file.tailPath);
        file.meta = new FileMeta(fingerprint, host, app, created, List.of(), now, now, 0, false, ArchiveTokens.newKey());
        file.writeMeta();
        syncDirectory(dir);
        return file;
    }

    /**
     * Loads an existing file and repairs what a crash may have left behind.
     *
     * @param root the {@code hot/} directory
     * @param metaPath path of the {@code .meta.json}
     * @param settings frame settings
     * @param json mapper for {@code .idx} and {@code .meta.json}
     * @param now current time, the age of pending bytes starts again
     * @return the loaded file
     * @throws IOException if the files cannot be read
     */
    static HotFile load(Path root, Path metaPath, HotProperties settings, JsonMapper json, Instant now)
        throws IOException {
        return load(root, metaPath, settings, json, now, FileChannelOpener.DEFAULT);
    }

    /**
     * Loads an existing file that writes through the given channels and repairs what a crash may have left behind.
     *
     * @param root the {@code hot/} directory
     * @param metaPath path of the {@code .meta.json}
     * @param settings frame settings
     * @param json mapper for {@code .idx} and {@code .meta.json}
     * @param now current time, the age of pending bytes starts again
     * @param opener opens all channels the file writes through
     * @return the loaded file
     * @throws IOException if the files cannot be read
     */
    static HotFile load(Path root, Path metaPath, HotProperties settings, JsonMapper json, Instant now,
        FileChannelOpener opener) throws IOException {
        String fileName = metaPath.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - META_SUFFIX.length());
        Path dir = metaPath.getParent();
        String relativeDir = root.relativize(dir).toString().replace('\\', '/');
        HotFile file = new HotFile(dir, name, relativeDir, settings, json, opener);
        file.meta = json.readValue(metaPath.toFile(), FileMeta.class);
        file.recover(now);
        if (file.meta.key() == null) {
            // a file from before the archive was pseudonymized
            file.meta = file.meta.withKey(ArchiveTokens.newKey());
            file.writeMeta();
        }
        return file;
    }

    private void recover(Instant now) throws IOException {
        FrameIndex.Recovered<Frame> index = FrameIndex.recover(idxPath, frameFormat(), opener);
        frames.addAll(index.frames());
        idxSize = index.size();
        loadProblems.addAll(index.problems());
        long zstSize = Files.exists(zstPath) ? Files.size(zstPath) : 0;
        int indexed = frames.size();
        while (!frames.isEmpty() && frames.getLast().zstEnd() > zstSize) {
            String problem = "frame at " + frames.getLast().raw() + " is missing in the zst file";
            LOGGER.error("{}: {}", zstPath, problem);
            loadProblems.add(problem);
            frames.removeLast();
        }
        if (frames.size() < indexed) {
            idxSize = FrameIndex.write(idxPath, frames, frameFormat(), opener);
        }
        long zstEnd = frames.isEmpty() ? 0 : frames.getLast().zstEnd();
        if (zstSize > zstEnd) {
            LOGGER.warn("{}: truncating unindexed frame data from {} to {} bytes", zstPath, zstSize, zstEnd);
            truncate(zstPath, zstEnd);
        }
        tailBase = frames.isEmpty() ? 0 : frames.getLast().rawEnd();
        if (Files.exists(tailPath)) {
            byte[] content = Files.readAllBytes(tailPath);
            if (content.length >= Long.BYTES) {
                long base = ByteBuffer.wrap(content).getLong();
                long skip = tailBase - base;
                if (skip < 0) {
                    LOGGER.error("{}: pending bytes start at {}, frames end at {}, bytes are lost", tailPath, base,
                        tailBase);
                } else if (skip < content.length - Long.BYTES) {
                    tail = Arrays.copyOfRange(content, Long.BYTES + (int) skip, content.length);
                    tailLength = tail.length;
                }
            }
            rewriteTail();
        }
        Files.deleteIfExists(tailPath.resolveSibling(tailPath.getFileName() + TMP_SUFFIX));
        Files.deleteIfExists(metaPath.resolveSibling(metaPath.getFileName() + TMP_SUFFIX));
        if (tailLength > 0) {
            tailSince = now;
        }
        if (meta.closed()) {
            flush(tailLength, now);
            closeChannel();
            Files.deleteIfExists(tailPath);
        }
    }

    /**
     * Appends bytes. Bytes the file already has are skipped, so a chunk may overlap with what is stored.
     *
     * @param offset offset of {@code data[0]} in the original file
     * @param data the bytes
     * @param length number of valid bytes in {@code data}
     * @param path path of the file on the host, recorded in the meta data
     * @param now receive time
     * @return the new status
     * @throws GapException if the chunk starts behind the end or the file is closed and the chunk has new bytes
     * @throws IOException if writing fails
     */
    public StreamStatus append(long offset, byte[] data, int length, String path, Instant now)
        throws GapException, IOException {
        lock.lock();
        try {
            checkNotDeleted();
            long end = length();
            if (offset > end) {
                throw new GapException("chunk starts at " + offset + " but stream ends at " + end, status());
            }
            recordPath(path);
            long skip = end - offset;
            if (skip >= length) {
                return status();
            }
            if (meta.closed()) {
                throw new GapException("stream is closed", status());
            }
            int from = (int) skip;
            int count = length - from;
            FileChannel channel = tailChannel();
            try {
                Appends.writeAt(channel, tailPath, Long.BYTES + tailLength, ByteBuffer.wrap(data, from, count));
            } catch (DamagedFileException e) {
                repairTail(e);
                throw e;
            }
            if (tail.length < tailLength + count) {
                tail = Arrays.copyOf(tail, Math.max(tailLength + count, tail.length * 2));
            }
            System.arraycopy(data, from, tail, tailLength, count);
            tailLength += count;
            if (tailSince == null) {
                tailSince = now;
            }
            meta = meta.withLength(meta.length(), now);
            cutFullFrames(now);
            return status();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks the file as completely transferred and writes all pending bytes into a frame.
     *
     * @param length the final length the agent has sent
     * @param path path of the file on the host, recorded in the meta data
     * @param now current time
     * @return the new status
     * @throws GapException if the stored length differs from {@code length}
     * @throws IOException if writing fails
     */
    public StreamStatus close(long length, String path, Instant now) throws GapException, IOException {
        lock.lock();
        try {
            checkNotDeleted();
            recordPath(path);
            if (length != length()) {
                throw new GapException("stream has " + length() + " bytes, not " + length, status());
            }
            if (!meta.closed()) {
                flush(tailLength, now);
                meta = meta.asClosed();
                writeMeta();
                closeChannel();
                Files.deleteIfExists(tailPath);
            }
            return status();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes complete lines into a frame if the oldest pending byte is older than {@code frame-max-age}.
     *
     * @param now current time
     * @throws IOException if writing fails
     */
    public void flushAged(Instant now) throws IOException {
        lock.lock();
        try {
            if (deleted) {
                return;
            }
            if (tailSince == null || tailSince.plus(settings.frameMaxAge()).isAfter(now)) {
                return;
            }
            int cut = lastNewline(0, tailLength) + 1;
            if (cut > 0) {
                flush(cut, now);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads bytes of the original file. At most one frame is read per call.
     *
     * @param offset offset in the original file
     * @param max maximum number of bytes
     * @return the bytes, empty if {@code offset} is at or behind the end
     * @throws IOException if reading fails
     */
    public byte[] read(long offset, int max) throws IOException {
        Frame frame;
        lock.lock();
        try {
            if (offset >= length() || max <= 0) {
                return new byte[0];
            }
            if (offset >= tailBase) {
                int from = (int) (offset - tailBase);
                return Arrays.copyOfRange(tail, from, from + Math.min(max, tailLength - from));
            }
            frame = frames.get(findFrame(offset));
        } finally {
            lock.unlock();
        }
        byte[] data = readFrame(frame);
        int from = (int) (offset - frame.raw());
        return Arrays.copyOfRange(data, from, from + Math.min(max, frame.rlen() - from));
    }

    private byte[] readFrame(Frame frame) throws IOException {
        byte[] compressed = new byte[frame.zlen()];
        try (FileChannel channel = FileChannel.open(zstPath, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(compressed);
            long position = frame.zst();
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position);
                if (read < 0) {
                    throw new IOException(zstPath + ": frame at " + frame.zst() + " is truncated");
                }
                position += read;
            }
        }
        return Zstd.decompress(compressed, frame.rlen());
    }

    private int findFrame(long offset) {
        int low = 0;
        int high = frames.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Frame frame = frames.get(mid);
            if (offset < frame.raw()) {
                high = mid - 1;
            } else if (offset >= frame.rawEnd()) {
                low = mid + 1;
            } else {
                return mid;
            }
        }
        throw new IllegalStateException("no frame for offset " + offset);
    }

    private void cutFullFrames(Instant now) throws IOException {
        int frameSize = (int) settings.frameSize().toBytes();
        while (tailLength >= frameSize) {
            int cut = lastNewline(0, frameSize) + 1;
            if (cut == 0) {
                cut = firstNewline(frameSize, tailLength) + 1;
            }
            if (cut == 0) {
                if (tailLength < 4L * frameSize) {
                    return;
                }
                cut = frameSize;
            }
            flush(cut, now);
        }
    }

    private int lastNewline(int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (tail[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private int firstNewline(int from, int to) {
        for (int i = from; i < to; i++) {
            if (tail[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void flush(int cut, Instant now) throws IOException {
        if (cut <= 0) {
            return;
        }
        byte[] compressed = Zstd.compress(Arrays.copyOf(tail, cut), settings.compressionLevel());
        long zstOffset = frames.isEmpty() ? 0 : frames.getLast().zstEnd();
        writeAt(zstPath, zstOffset, compressed);
        Frame frame = new Frame(zstOffset, compressed.length, tailBase, cut, meta.lastReceived());
        byte[] line = (json.writeValueAsString(frame) + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            writeAt(idxPath, idxSize, line);
        } catch (IOException e) {
            // the frame is not indexed, its data are cut off by the next write or on load anyway
            Appends.cutBackQuietly(opener, zstPath, zstOffset);
            throw e;
        }
        idxSize += line.length;
        frames.add(frame);
        System.arraycopy(tail, cut, tail, 0, tailLength - cut);
        tailLength -= cut;
        tailBase += cut;
        tailSince = tailLength > 0 ? now : null;
        if (tail.length > 2 * (int) settings.frameSize().toBytes()) {
            tail = Arrays.copyOf(tail, Math.max(tailLength, (int) settings.frameSize().toBytes()));
        }
        rewriteTail();
        meta = meta.withLength(length(), meta.lastReceived());
        writeMeta();
    }

    /**
     * Writes bytes at the end of the valid data of {@code .log.zst} or {@code .idx} and keeps track of damage.
     */
    private void writeAt(Path path, long end, byte[] data) throws IOException {
        try {
            Appends.writeAt(opener, path, end, data);
        } catch (DamagedFileException e) {
            markDamaged(path, e);
            throw e;
        }
        if (damaged.remove(path) != null) {
            LOGGER.info("{}: repaired, appending again", path);
        }
    }

    private void markDamaged(Path path, DamagedFileException e) {
        LOGGER.error("{} is damaged, nothing is appended until it is repaired", path, e);
        damaged.put(path, e.getMessage());
    }

    /**
     * A failed append to the {@code .tail} could not be cut back. Writes the {@code .tail} again from memory; if
     * that fails as well, the next append tries it again before writing anything.
     */
    private void repairTail(DamagedFileException e) {
        markDamaged(tailPath, e);
        try {
            rewriteTail();
            damaged.remove(tailPath);
            LOGGER.info("{}: written again from memory", tailPath);
        } catch (IOException rewrite) {
            e.addSuppressed(rewrite);
        }
    }

    private void rewriteTail() throws IOException {
        closeChannel();
        tailDirty = true;
        Path tmp = tailPath.resolveSibling(tailPath.getFileName() + TMP_SUFFIX);
        try (FileChannel channel = opener.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES + tailLength);
            buffer.putLong(tailBase).put(tail, 0, tailLength).flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        }
        Files.move(tmp, tailPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        tailDirty = false;
    }

    private FileChannel tailChannel() throws IOException {
        if (tailChannel == null) {
            if (tailDirty || !Files.exists(tailPath)) {
                rewriteTail();
                if (damaged.remove(tailPath) != null) {
                    LOGGER.info("{}: written again from memory, appending again", tailPath);
                }
            }
            // no APPEND: bytes are written where the valid data end, see Appends
            tailChannel = opener.open(tailPath, StandardOpenOption.WRITE);
        }
        return tailChannel;
    }

    private void closeChannel() throws IOException {
        if (tailChannel != null) {
            tailChannel.close();
            tailChannel = null;
        }
    }

    private void recordPath(String path) throws IOException {
        FileMeta updated = meta.withPath(path);
        if (updated != meta) {
            meta = updated;
            writeMeta();
        }
    }

    private void writeMeta() throws IOException {
        Path tmp = metaPath.resolveSibling(metaPath.getFileName() + TMP_SUFFIX);
        try (FileChannel channel = opener.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(json.writeValueAsBytes(meta));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        }
        Files.move(tmp, metaPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void truncate(Path path, long size) throws IOException {
        try (FileChannel channel = opener.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(size);
            channel.force(false);
        }
    }

    private static void syncDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            LOGGER.debug("cannot sync directory {}", dir, e);
        }
    }

    /**
     * Deletes the file from disk. The meta data go first, so a crash leaves only data that is never loaded.
     *
     * @throws IOException if deleting fails
     */
    void delete() throws IOException {
        lock.lock();
        try {
            deleted = true;
            closeChannel();
            Files.deleteIfExists(metaPath);
            syncDirectory(metaPath.getParent());
            Files.deleteIfExists(zstPath);
            Files.deleteIfExists(idxPath);
            Files.deleteIfExists(tailPath);
            Files.deleteIfExists(metaPath.resolveSibling(metaPath.getFileName() + TMP_SUFFIX));
        } finally {
            lock.unlock();
        }
    }

    private void checkNotDeleted() throws IOException {
        if (deleted) {
            throw new IOException(relativePath + " was deleted");
        }
    }

    /**
     * Releases the open file handle. The file can still be used, the handle is opened again when needed.
     *
     * @throws IOException if closing fails
     */
    void release() throws IOException {
        lock.lock();
        try {
            closeChannel();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Current status as reported to the agent.
     *
     * @return fingerprint, length and closed flag
     */
    public StreamStatus status() {
        lock.lock();
        try {
            return new StreamStatus(meta.fingerprint(), length(), meta.closed());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Number of bytes stored.
     *
     * @return offset of the next byte
     */
    public long length() {
        lock.lock();
        try {
            return tailBase + tailLength;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Current meta data.
     *
     * @return the meta data, its length may lag behind {@link #length()}
     */
    public FileMeta meta() {
        lock.lock();
        try {
            return meta;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The fingerprint of the file.
     *
     * @return the fingerprint
     */
    public String fingerprint() {
        return meta().fingerprint();
    }

    /**
     * Path of the compressed file relative to {@code hot/} and {@code archive/}.
     *
     * @return e.g. {@code app1/digibib/2026-10-23-<fingerprint>.log.zst}
     */
    public String relativePath() {
        return relativePath;
    }

    /**
     * Number of bytes already written into frames. Frames end at line ends unless a line is longer than four
     * frames, so this is where the complete lines end.
     *
     * @return end of the last frame in the original file
     */
    public long framedLength() {
        lock.lock();
        try {
            return tailBase;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Receive time of the bytes at an offset.
     *
     * @param offset offset in the original file
     * @return receive time of the frame containing the offset, the last receive time for pending bytes
     */
    public Instant received(long offset) {
        lock.lock();
        try {
            if (offset >= tailBase || frames.isEmpty()) {
                return meta.lastReceived();
            }
            return frames.get(findFrame(Math.max(0, offset))).received();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Problems with the files on disk: what was repaired with loss on load, and files that are damaged now because a
     * failed write could not be undone.
     *
     * @return messages, empty if everything is fine
     */
    public List<String> problems() {
        lock.lock();
        try {
            List<String> result = new ArrayList<>(loadProblems);
            result.addAll(damaged.values());
            return result;
        } finally {
            lock.unlock();
        }
    }

    private FrameIndex.Format<Frame> frameFormat() {
        return new FrameIndex.Format<>() {

            @Override
            public Frame parse(String line) {
                return json.readValue(line, Frame.class);
            }

            @Override
            public String format(Frame frame) {
                return json.writeValueAsString(frame);
            }

            @Override
            public boolean follows(Frame previous, Frame next) {
                long zst = previous == null ? 0 : previous.zstEnd();
                long raw = previous == null ? 0 : previous.rawEnd();
                return next.zst() == zst && next.raw() == raw && next.zlen() > 0 && next.rlen() > 0
                    && next.received() != null;
            }
        };
    }

    /**
     * Number of frames written.
     *
     * @return the number of entries in the {@code .idx}
     */
    public int frameCount() {
        lock.lock();
        try {
            return frames.size();
        } finally {
            lock.unlock();
        }
    }
}
