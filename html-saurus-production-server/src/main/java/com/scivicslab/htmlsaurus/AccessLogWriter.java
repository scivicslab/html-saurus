package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.LongSupplier;

/**
 * Owns the access log's file. A plain object: {@link AccessLog} runs it as an actor, so every line is written by one
 * message at a time and lines never interleave.
 *
 * <p>The file is opened for appending and kept open. At most once a second the path is looked at: when it names another
 * file than the one held open (logrotate renamed the log and made a new one) or no file (it was deleted), the old file is
 * closed and the path is opened again, so an ordinary logrotate configuration without {@code copytruncate} works.
 * A file is created with mode 0640. An existing one keeps its mode.
 *
 * <p>A failure to open or write never reaches the caller: it is counted, and the first one is reported on standard error.
 * A line that could not be written is lost; the next one tries again, at most once a second.
 */
final class AccessLogWriter {

    private static final long CHECK_INTERVAL_NANOS = 1_000_000_000L;

    private final Path file;
    private final OutputStream stream;
    private final LongSupplier nanoClock;
    private FileChannel channel;
    private Object openedKey;
    private long nextCheck;
    private long errors;
    private long lost;
    private boolean reported;

    /** A writer of the file {@code file}, whose clock for the once-a-second check is {@code nanoClock}. */
    AccessLogWriter(Path file, LongSupplier nanoClock) {
        this.file = file;
        this.stream = null;
        this.nanoClock = nanoClock;
        reopen();
        this.nextCheck = nanoClock.getAsLong() + CHECK_INTERVAL_NANOS;
    }

    /** A writer of a stream, such as standard output, which has nothing to rotate. */
    AccessLogWriter(OutputStream stream) {
        this.file = null;
        this.stream = stream;
        this.nanoClock = System::nanoTime;
    }

    /** Writes one line and its line break. */
    void append(String line) {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            if (stream != null) {
                stream.write(bytes);
                stream.flush();
                return;
            }
            long now = nanoClock.getAsLong();
            if (now - nextCheck >= 0) {
                nextCheck = now + CHECK_INTERVAL_NANOS;
                if (channel == null || rotated()) reopen();
            }
            if (channel == null) {
                lost++;   // the open that failed was counted and reported when it was tried
                return;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
        } catch (IOException | RuntimeException e) {
            lost++;
            failed(e);
        }
    }

    /** Closes the file and, if any line was lost, says how many. */
    void close() {
        try {
            if (channel != null) channel.close();
        } catch (IOException e) {
            failed(e);
        }
        channel = null;
        if (lost > 0) System.err.println("html-saurus access log: " + lost + " lines could not be written");
    }

    /** @return how many opens and writes failed */
    long errors() {
        return errors;
    }

    /** @return how many lines were lost */
    long lost() {
        return lost;
    }

    private boolean rotated() {
        try {
            Object key = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
            return key != null && !key.equals(openedKey);
        } catch (NoSuchFileException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void reopen() {
        try {
            if (channel != null) channel.close();
        } catch (IOException ignored) {
            // The old file is being left; nothing to do with a failure to close it.
        }
        channel = null;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try {
                channel = FileChannel.open(file, java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r-----")));
            } catch (UnsupportedOperationException e) {
                channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            }
            openedKey = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        } catch (IOException | RuntimeException e) {
            channel = null;
            failed(e);
        }
    }

    private void failed(Exception e) {
        errors++;
        if (!reported) {
            reported = true;
            System.err.println("html-saurus access log: cannot write " + (file == null ? "to the stream" : file) + ": " + e
                    + " (further failures are counted, not printed)");
        }
    }
}
