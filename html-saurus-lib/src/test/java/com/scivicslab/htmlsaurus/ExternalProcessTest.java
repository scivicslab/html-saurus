package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Pins {@link ExternalProcess}: the time limit holds whatever the program prints, and the output comes back whole. */
class ExternalProcessTest {

    private static Path script(Path dir, String body) throws Exception {
        Path s = dir.resolve("tool.sh");
        Files.writeString(s, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(s, PosixFilePermissions.fromString("rwxr-xr-x"));
        return s;
    }

    @Test
    void theOutputAndTheExitCodeComeBack(@TempDir Path dir) throws Exception {
        Path s = script(dir, "echo out; echo err 1>&2; exit 3");
        ExternalProcess.Result r = ExternalProcess.run(List.of(s.toString()), null, Duration.ofSeconds(30));
        assertFalse(r.timedOut());
        assertEquals(3, r.exitCode());
        assertTrue(r.output().contains("out") && r.output().contains("err"), r.output());
        assertFalse(r.succeeded());
    }

    @Test
    void aProgramThatPrintsNothingAndNeverEndsIsKilledWhenTheLimitPasses(@TempDir Path dir) throws Exception {
        Path s = script(dir, "sleep 60");
        long t0 = System.nanoTime();
        ExternalProcess.Result r = ExternalProcess.run(List.of(s.toString()), null, Duration.ofMillis(700));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(r.timedOut());
        assertEquals(-1, r.exitCode());
        assertTrue(ms < 15_000, "returned after " + ms + " ms, not after the program's own sleep");
    }

    @Test
    void aProgramThatPrintsMoreThanAPipeHoldsStillFinishes(@TempDir Path dir) throws Exception {
        Path s = script(dir, "head -c 5000000 /dev/zero | tr '\\0' 'x'");
        ExternalProcess.Result r = ExternalProcess.run(List.of(s.toString()), null, Duration.ofSeconds(60));
        assertTrue(r.succeeded());
        assertEquals(5_000_000, r.output().length());
    }

    @Test
    void theProgramRunsInTheGivenDirectory(@TempDir Path dir) throws Exception {
        Path work = Files.createDirectories(dir.resolve("work"));
        Path s = script(dir, "pwd");
        ExternalProcess.Result r = ExternalProcess.run(List.of(s.toString()), work, Duration.ofSeconds(30));
        assertEquals(work.toRealPath().toString(), r.output().trim());
    }

    @Test
    void aProgramThatCannotBeStartedIsAnIoException(@TempDir Path dir) {
        assertThrows(java.io.IOException.class,
                () -> ExternalProcess.run(List.of(dir.resolve("missing").toString()), null, Duration.ofSeconds(5)));
    }

    @Test
    void aVirtualThreadMayWaitForTheProgram(@TempDir Path dir) throws Exception {
        Path s = script(dir, "echo hello");
        AtomicReference<ExternalProcess.Result> got = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                got.set(ExternalProcess.run(List.of(s.toString()), null, Duration.ofSeconds(30)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        t.join(60_000);
        assertEquals("hello", got.get().output().trim());
    }

    @Test
    void anInterruptedWaitKillsTheProgramAndPassesTheInterruptOn(@TempDir Path dir) throws Exception {
        Path s = script(dir, "sleep 60");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                ExternalProcess.run(List.of(s.toString()), null, Duration.ofSeconds(120));
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        Thread.sleep(500);
        t.interrupt();
        t.join(30_000);
        assertInstanceOf(InterruptedException.class, thrown.get());
    }
}
