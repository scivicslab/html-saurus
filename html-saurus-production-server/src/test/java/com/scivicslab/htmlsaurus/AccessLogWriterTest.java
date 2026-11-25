package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file is kept open, reopened when logrotate moves or removes it, created 0640, and a failure never escapes. */
class AccessLogWriterTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void theFileIsCreatedWithAParentDirectoryAndMode0640(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("logs/access.log");
        AccessLogWriter w = new AccessLogWriter(file, new AtomicLong()::get);
        w.append("one");
        w.close();
        assertEquals(List.of("one"), Files.readAllLines(file));
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void anExistingFileIsAppendedToAndKeepsItsMode(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("access.log");
        Files.writeString(file, "old\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw----"));
        AccessLogWriter w = new AccessLogWriter(file, new AtomicLong()::get);
        w.append("new");
        w.close();
        assertEquals(List.of("old", "new"), Files.readAllLines(file));
        assertEquals("rw-rw----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void afterLogrotateRenamesTheFileTheNextLineGoesToANewOneAtThePath(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("access.log");
        AtomicLong clock = new AtomicLong();
        AccessLogWriter w = new AccessLogWriter(file, clock::get);
        w.append("before");
        Files.move(file, dir.resolve("access.log.1"));          // what logrotate does without copytruncate
        w.append("still the old file");                         // less than a second later: the path is not looked at yet
        assertEquals(List.of("before", "still the old file"), Files.readAllLines(dir.resolve("access.log.1")));
        clock.addAndGet(2 * SECOND);
        w.append("after");
        w.close();
        assertEquals(List.of("after"), Files.readAllLines(file), "the new file at the path holds the new line");
        assertEquals(List.of("before", "still the old file"), Files.readAllLines(dir.resolve("access.log.1")));
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void afterTheFileIsDeletedTheNextLineCreatesIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("access.log");
        AtomicLong clock = new AtomicLong();
        AccessLogWriter w = new AccessLogWriter(file, clock::get);
        w.append("before");
        Files.delete(file);
        clock.addAndGet(2 * SECOND);
        w.append("after");
        w.close();
        assertEquals(List.of("after"), Files.readAllLines(file));
    }

    @Test
    void aFileThatIsNotMovedIsNotReopened(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("access.log");
        AtomicLong clock = new AtomicLong();
        AccessLogWriter w = new AccessLogWriter(file, clock::get);
        for (int i = 0; i < 5; i++) { clock.addAndGet(2 * SECOND); w.append("line " + i); }
        w.close();
        assertEquals(5, Files.readAllLines(file).size());
        assertEquals(0, w.errors());
    }

    @Test
    void aFailureIsCountedAndReportedOnceAndNothingIsThrown(@TempDir Path dir) throws Exception {
        Path blocker = dir.resolve("not-a-directory");
        Files.writeString(blocker, "x");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream saved = System.err;
        System.setErr(new PrintStream(err, true));
        try {
            AtomicLong clock = new AtomicLong();
            AccessLogWriter w = new AccessLogWriter(blocker.resolve("access.log"), clock::get);
            for (int i = 0; i < 4; i++) { clock.addAndGet(2 * SECOND); w.append("line " + i); }
            w.close();
            assertEquals(5, w.errors(), "the open at start and each retry");
            assertEquals(4, w.lost());
        } finally {
            System.setErr(saved);
        }
        String printed = err.toString();
        assertEquals(1, printed.split("cannot write", -1).length - 1, "reported once: " + printed);
        assertTrue(printed.contains("4 lines could not be written"), printed);
    }

    @Test
    void aWriterRecoversWhenThePathBecomesWritable(@TempDir Path dir) throws Exception {
        Path blocker = dir.resolve("dir");
        Files.writeString(blocker, "in the way");
        PrintStream saved = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
        try {
            AtomicLong clock = new AtomicLong();
            AccessLogWriter w = new AccessLogWriter(blocker.resolve("access.log"), clock::get);
            w.append("lost");
            Files.delete(blocker);
            clock.addAndGet(2 * SECOND);
            w.append("kept");
            w.close();
            assertEquals(List.of("kept"), Files.readAllLines(dir.resolve("dir/access.log")));
        } finally {
            System.setErr(saved);
        }
    }

    @Test
    void aStreamGetsOneLinePerCall() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AccessLogWriter w = new AccessLogWriter(out);
        w.append("a");
        w.append("b");
        assertEquals("a\nb\n", out.toString());
    }

    @Test
    void linesFromManyThreadsThroughTheActorAreNeverInterleaved(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("access.log");
        try (AccessLog log = new AccessLog(new AccessLogWriter(file, System::nanoTime), TrustedProxies.NONE, false,
                java.time.Clock.systemDefaultZone())) {
            List<Thread> threads = new java.util.ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int n = t;
                threads.add(Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < 200; i++) log.record("line-" + n + "-" + i + "-" + "x".repeat(300));
                }));
            }
            for (Thread t : threads) t.join();
        }
        List<String> lines = Files.readAllLines(file);
        assertEquals(1600, lines.size());
        for (String l : lines) assertTrue(l.matches("line-\\d-\\d+-x{300}"), l);
    }
}
