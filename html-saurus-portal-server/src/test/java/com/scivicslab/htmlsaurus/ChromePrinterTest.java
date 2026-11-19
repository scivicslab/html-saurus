package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@link ChromePrinter} leaves behind when printing does not finish (ExportToPdf_261119_oo01). A stand-in script
 * plays Chrome: it starts a child process, as Chrome does, and never exits by itself.
 */
@Tag("ExportToPdf_261119_oo01")
class ChromePrinterTest {

    @TempDir
    Path dir;

    /** A script that starts a long-running child, writes the child's process id to {@code child.pid}, and waits for it. */
    private Path hangingChrome() throws IOException {
        Path script = dir.resolve("fake-chrome.sh");
        Files.writeString(script, "#!/bin/sh\nsleep 300 &\necho $! > \"$(dirname \"$0\")/child.pid\"\nwait\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private long childPid() throws Exception {
        Path file = dir.resolve("child.pid");
        for (int i = 0; i < 100 && !(Files.isRegularFile(file) && Files.size(file) > 0); i++) Thread.sleep(50);
        return Long.parseLong(Files.readString(file).strip());
    }

    /** A killed process that nobody has waited for yet is a zombie: the system still lists it, and it runs nothing. */
    private static boolean isZombie(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
            return stat.substring(stat.lastIndexOf(')') + 2).startsWith("Z");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isGone(long pid) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive() || isZombie(pid)) return true;
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void printHtmlToPdf_whenChromeRunsPastTheTimeout_killsChromeAndItsChildProcesses() throws Exception {
        ChromePrinter printer = new ChromePrinter(hangingChrome());
        Path html = Files.writeString(dir.resolve("in.html"), "<p>x</p>");
        IOException failure = assertThrows(IOException.class,
                () -> printer.printHtmlToPdf(html, dir.resolve("out.pdf"), dir.resolve("work"), Duration.ofSeconds(1)));
        assertTrue(failure.getMessage().contains("did not finish"), failure.getMessage());
        assertTrue(isGone(childPid()), "the child process of Chrome is still running");
    }

    @Test
    void printHtmlToPdf_whenTheThreadIsInterrupted_killsChromeAndItsChildProcessesAndKeepsTheInterrupt() throws Exception {
        ChromePrinter printer = new ChromePrinter(hangingChrome());
        Path html = Files.writeString(dir.resolve("in.html"), "<p>x</p>");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> flagKept = new AtomicReference<>(false);
        Thread worker = new Thread(() -> {
            try {
                printer.printHtmlToPdf(html, dir.resolve("out.pdf"), dir.resolve("work"), Duration.ofMinutes(5));
            } catch (Throwable t) {
                thrown.set(t);
                flagKept.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        long pid = childPid();
        worker.interrupt();
        worker.join(10_000);
        assertFalse(worker.isAlive(), "the printing thread did not stop");
        assertTrue(thrown.get() instanceof InterruptedException, "thrown: " + thrown.get());
        assertTrue(flagKept.get(), "the interrupt flag is set again before the exception is thrown on");
        assertTrue(isGone(pid), "the child process of Chrome is still running");
    }
}
