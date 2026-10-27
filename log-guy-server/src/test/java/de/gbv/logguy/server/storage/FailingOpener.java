package de.gbv.logguy.server.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Opens channels that fail like a full disk: after a number of bytes, writes throw an {@link IOException}, the bytes
 * up to there stay in the file. Truncating can be made to fail as well. Only files matching a predicate are
 * affected, all others are opened normally.
 */
public final class FailingOpener implements FileChannelOpener {

    private final Predicate<Path> affected;

    private long budget = Long.MAX_VALUE;

    private boolean failTruncate;

    /**
     * Creates an opener that does not fail yet.
     *
     * @param affected files whose writes fail
     */
    public FailingOpener(Predicate<Path> affected) {
        this.affected = affected;
    }

    /**
     * Creates an opener for files whose names end with one of the suffixes.
     *
     * @param suffixes e.g. {@code .tail}
     * @return the opener
     */
    public static FailingOpener forSuffixes(String... suffixes) {
        return new FailingOpener(path -> {
            String name = path.getFileName().toString();
            for (String suffix : suffixes) {
                if (name.endsWith(suffix)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Lets the affected files take some more bytes, then fails.
     *
     * @param bytes bytes written before the failure
     * @return this opener
     */
    public synchronized FailingOpener failAfter(long bytes) {
        budget = bytes;
        return this;
    }

    /**
     * Makes truncating the affected files fail as well.
     *
     * @return this opener
     */
    public synchronized FailingOpener failTruncate() {
        failTruncate = true;
        return this;
    }

    /**
     * Space is free again, nothing fails any more.
     */
    public synchronized void heal() {
        budget = Long.MAX_VALUE;
        failTruncate = false;
    }

    @Override
    public FileChannel open(Path path, OpenOption... options) throws IOException {
        FileChannel channel = FileChannel.open(path, options);
        return affected.test(path) ? new FailingChannel(channel) : channel;
    }

    private synchronized int allow(int wanted) throws IOException {
        if (wanted > 0 && budget == 0) {
            throw new IOException("No space left on device");
        }
        int allowed = (int) Math.min(wanted, budget);
        if (budget != Long.MAX_VALUE) {
            budget -= allowed;
        }
        return allowed;
    }

    private synchronized void checkTruncate() throws IOException {
        if (failTruncate) {
            throw new IOException("Input/output error");
        }
    }

    private final class FailingChannel extends FileChannel {

        private final FileChannel delegate;

        FailingChannel(FileChannel delegate) {
            this.delegate = delegate;
        }

        private int limited(ByteBuffer source, long position, boolean positional) throws IOException {
            int allowed = allow(source.remaining());
            ByteBuffer slice = source.slice(source.position(), allowed);
            int written = positional ? delegate.write(slice, position) : delegate.write(slice);
            source.position(source.position() + written);
            return written;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return limited(src, -1, false);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            return limited(src, position, true);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            long total = 0;
            for (int i = offset; i < offset + length; i++) {
                total += write(srcs[i]);
            }
            return total;
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            checkTruncate();
            delegate.truncate(size);
            return this;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return delegate.read(dsts, offset, length);
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return delegate.read(dst, position);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public void force(boolean metaData) throws IOException {
            delegate.force(metaData);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return delegate.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }
    }
}
