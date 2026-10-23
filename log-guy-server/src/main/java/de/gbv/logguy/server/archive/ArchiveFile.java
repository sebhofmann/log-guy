package de.gbv.logguy.server.archive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.luben.zstd.Zstd;

import de.gbv.logguy.server.hot.FileMeta;
import tools.jackson.databind.json.JsonMapper;

/**
 * One filtered log file in {@code archive/}, append only.
 * <p>
 * Three files belong together, named like in {@code hot/}: {@code .log.zst} with independent zstd frames,
 * {@code .idx} with one {@link ArchiveFrame} per line and {@code .meta.json} with {@link ArchiveMeta}. A frame is
 * first synced to the {@code .log.zst}, then to the {@code .idx}, then the meta data are replaced. After a crash,
 * data behind the last complete index line are cut off and the totals in the meta data are counted again, so the
 * archive writer continues with the first original byte not in a frame.
 */
public final class ArchiveFile {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String ZST_SUFFIX = ".log.zst";

    private static final String IDX_SUFFIX = ".idx";

    /** Suffix of the meta file, used to find all files on startup. */
    static final String META_SUFFIX = ".meta.json";

    private static final String TMP_SUFFIX = ".tmp";

    private final Path zstPath;

    private final Path idxPath;

    private final Path metaPath;

    private final String relativePath;

    private final JsonMapper json;

    private final ReentrantLock lock = new ReentrantLock();

    private final List<ArchiveFrame> frames = new ArrayList<>();

    private ArchiveMeta meta;

    private ArchiveFile(Path dir, String name, String relativeDir, JsonMapper json) {
        this.zstPath = dir.resolve(name + ZST_SUFFIX);
        this.idxPath = dir.resolve(name + IDX_SUFFIX);
        this.metaPath = dir.resolve(name + META_SUFFIX);
        this.relativePath = relativeDir + "/" + name + ZST_SUFFIX;
        this.json = json;
    }

    /**
     * Creates a new, empty file for a file in {@code hot/}.
     *
     * @param root the {@code archive/} directory
     * @param source meta data of the original file
     * @param json mapper for {@code .idx} and {@code .meta.json}
     * @return the new file
     * @throws IOException if the files cannot be written
     */
    static ArchiveFile create(Path root, FileMeta source, JsonMapper json) throws IOException {
        String relativeDir = source.host() + "/" + source.app();
        Path dir = root.resolve(relativeDir);
        Files.createDirectories(dir);
        ArchiveFile file = new ArchiveFile(dir, source.created() + "-" + source.fingerprint(), relativeDir, json);
        file.meta = new ArchiveMeta(source.fingerprint(), source.host(), source.app(), source.created(),
            source.paths(), 0, 0, 0, null, null, false, List.of(), 0);
        file.writeMeta();
        syncDirectory(dir);
        return file;
    }

    /**
     * Loads an existing file and repairs what a crash may have left behind.
     *
     * @param root the {@code archive/} directory
     * @param metaPath path of the {@code .meta.json}
     * @param json mapper for {@code .idx} and {@code .meta.json}
     * @return the loaded file
     * @throws IOException if the files cannot be read
     */
    static ArchiveFile load(Path root, Path metaPath, JsonMapper json) throws IOException {
        String fileName = metaPath.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - META_SUFFIX.length());
        Path dir = metaPath.getParent();
        String relativeDir = root.relativize(dir).toString().replace('\\', '/');
        ArchiveFile file = new ArchiveFile(dir, name, relativeDir, json);
        file.meta = json.readValue(metaPath.toFile(), ArchiveMeta.class);
        file.recover();
        return file;
    }

    private void recover() throws IOException {
        if (Files.exists(idxPath)) {
            byte[] idx = Files.readAllBytes(idxPath);
            int valid = 0;
            int start = 0;
            for (int i = 0; i < idx.length; i++) {
                if (idx[i] == '\n') {
                    String line = new String(idx, start, i - start, StandardCharsets.UTF_8);
                    try {
                        frames.add(json.readValue(line, ArchiveFrame.class));
                    } catch (RuntimeException e) {
                        LOGGER.warn("{}: ignoring broken index line after frame {}", idxPath, frames.size());
                        break;
                    }
                    valid = i + 1;
                    start = i + 1;
                }
            }
            if (valid < idx.length) {
                LOGGER.warn("{}: truncating index from {} to {} bytes", idxPath, idx.length, valid);
                truncate(idxPath, valid);
            }
        }
        long zstSize = Files.exists(zstPath) ? Files.size(zstPath) : 0;
        while (!frames.isEmpty() && frames.getLast().zstEnd() > zstSize) {
            LOGGER.error("{}: frame at {} is missing in the zst file", zstPath, frames.getLast().raw());
            frames.removeLast();
        }
        long zstEnd = frames.isEmpty() ? 0 : frames.getLast().zstEnd();
        if (zstSize > zstEnd) {
            LOGGER.warn("{}: truncating unindexed frame data from {} to {} bytes", zstPath, zstSize, zstEnd);
            truncate(zstPath, zstEnd);
        }
        long sourceEnd = frames.isEmpty() ? 0 : frames.getLast().srcEnd();
        if (meta.sourceLength() != sourceEnd || meta.length() != (frames.isEmpty() ? 0 : frames.getLast()
            .rawEnd())) {
            LOGGER.warn("{}: meta data say {} original bytes, frames end at {}, counting again", metaPath,
                meta.sourceLength(), sourceEnd);
            meta = meta.recount(frames);
            writeMeta();
        }
        Files.deleteIfExists(metaPath.resolveSibling(metaPath.getFileName() + TMP_SUFFIX));
    }

    /**
     * Appends a frame.
     *
     * @param data the filtered lines
     * @param src offset of the first original byte
     * @param srcEnd end of the original bytes
     * @param lines number of lines in {@code data}
     * @param errors lines replaced by the placeholder
     * @param from time of the first event
     * @param to time of the last event
     * @param received when the last original byte arrived
     * @param labels filters the lines went through
     * @param paths current paths of the original file
     * @param compressionLevel zstd level
     * @throws IOException if writing fails
     */
    public void append(byte[] data, long src, long srcEnd, int lines, int errors, Instant from,
        Instant to, Instant received, List<String> labels, List<String> paths,
        int compressionLevel) throws IOException {
        lock.lock();
        try {
            if (meta.closed()) {
                throw new IllegalStateException(relativePath + " is closed");
            }
            if (src != meta.sourceLength()) {
                throw new IllegalStateException(relativePath + ": frame starts at " + src + ", archived are "
                    + meta.sourceLength());
            }
            long zstOffset = frames.isEmpty() ? 0 : frames.getLast().zstEnd();
            byte[] compressed = data.length == 0 ? new byte[0] : Zstd.compress(data, compressionLevel);
            if (compressed.length > 0) {
                try (FileChannel channel = FileChannel.open(zstPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(compressed);
                    long position = zstOffset;
                    while (buffer.hasRemaining()) {
                        position += channel.write(buffer, position);
                    }
                    channel.force(false);
                }
            }
            ArchiveFrame frame = new ArchiveFrame(zstOffset, compressed.length, meta.length(), data.length, src,
                srcEnd, lines, errors, from, to, received);
            byte[] line = (json.writeValueAsString(frame) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(idxPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(false);
            }
            frames.add(frame);
            meta = meta.with(frame, labels, paths);
            writeMeta();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks the file as complete, after the original file was closed and completely archived.
     *
     * @param paths current paths of the original file
     * @throws IOException if writing fails
     */
    public void close(List<String> paths) throws IOException {
        lock.lock();
        try {
            if (!meta.closed()) {
                meta = meta.asClosed(paths);
                writeMeta();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads the filtered lines of a frame.
     *
     * @param frame a frame of this file
     * @return the uncompressed content
     * @throws IOException if reading fails
     */
    public byte[] readFrame(ArchiveFrame frame) throws IOException {
        if (frame.zlen() == 0) {
            return new byte[0];
        }
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

    /**
     * Reads bytes of the filtered file. At most one frame is read per call.
     *
     * @param offset offset in the filtered file
     * @param max maximum number of bytes
     * @return the bytes, empty if {@code offset} is at or behind the end
     * @throws IOException if reading fails
     */
    public byte[] read(long offset, int max) throws IOException {
        ArchiveFrame frame;
        lock.lock();
        try {
            if (offset >= meta.length() || max <= 0) {
                return new byte[0];
            }
            frame = frames.get(findFrame(offset));
        } finally {
            lock.unlock();
        }
        byte[] data = readFrame(frame);
        int from = (int) (offset - frame.raw());
        return Arrays.copyOfRange(data, from, from + Math.min(max, frame.rlen() - from));
    }

    /**
     * Finds the frame containing an offset below the length: the last frame starting at or before it. Frames whose
     * lines were all dropped have no content and start where the next frame starts, so they are never the last.
     */
    private int findFrame(long offset) {
        int low = 0;
        int high = frames.size() - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (frames.get(mid).raw() <= offset) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (found < 0 || offset >= frames.get(found).rawEnd()) {
            throw new IllegalStateException("no frame for offset " + offset);
        }
        return found;
    }

    private void writeMeta() throws IOException {
        Path tmp = metaPath.resolveSibling(metaPath.getFileName() + TMP_SUFFIX);
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(json.writeValueAsBytes(meta));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        }
        Files.move(tmp, metaPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void truncate(Path path, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
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
     * Current meta data.
     *
     * @return the meta data
     */
    public ArchiveMeta meta() {
        lock.lock();
        try {
            return meta;
        } finally {
            lock.unlock();
        }
    }

    /**
     * All frames written so far.
     *
     * @return a copy of the frame index
     */
    public List<ArchiveFrame> frames() {
        lock.lock();
        try {
            return List.copyOf(frames);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Size of the compressed file.
     *
     * @return bytes in the {@code .log.zst}
     */
    public long compressedSize() {
        lock.lock();
        try {
            return frames.isEmpty() ? 0 : frames.getLast().zstEnd();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The fingerprint of the original file.
     *
     * @return the fingerprint
     */
    public String fingerprint() {
        return meta().fingerprint();
    }

    /**
     * Path of the compressed file relative to {@code archive/}, the same as in {@code hot/}.
     *
     * @return e.g. {@code app1/digibib/2026-10-23-<fingerprint>.log.zst}
     */
    public String relativePath() {
        return relativePath;
    }
}
