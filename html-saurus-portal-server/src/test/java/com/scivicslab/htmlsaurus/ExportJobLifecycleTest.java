package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An export from the press of Make PDF to Clear (ExportToPdf_261119_oo01), over HTTP, with a script in the place of
 * Chrome: Stop ends a printing and takes Chrome's child processes with it, a finished PDF can be downloaded, and Clear
 * deletes the work directory of a job whatever way it ended.
 */
@Tag("ExportToPdf_261119_oo01")
class ExportJobLifecycleTest {

    @TempDir
    Path tempDir;

    private ExportPortalTestSupport portal;

    @AfterEach
    void close() {
        if (portal != null) portal.close();
    }

    /** A Chrome that starts a long-running child, as Chrome starts its renderers, and then waits for it. */
    private Path hangingChrome() throws IOException {
        return portal.script("hanging-chrome.sh", "sleep 300 &\necho $! > \"$(dirname \"$0\")/child.pid\"\nwait\n");
    }

    private long childPid() throws Exception {
        Path file = tempDir.resolve("child.pid");
        for (int i = 0; i < 200 && !(Files.isRegularFile(file) && Files.size(file) > 0); i++) Thread.sleep(50);
        return Long.parseLong(Files.readString(file).strip());
    }

    private static boolean isGone(long pid) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            boolean zombie = false;
            try {
                String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
                zombie = stat.substring(stat.lastIndexOf(')') + 2).startsWith("Z");
            } catch (IOException | RuntimeException e) {
                zombie = false;
            }
            if (handle.isEmpty() || !handle.get().isAlive() || zombie) return true;
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void stop_whileChromeIsPrinting_endsTheJobAndKillsChromesChildProcesses() throws Exception {
        portal = new ExportPortalTestSupport(tempDir);
        String base = portal.startPortal();
        portal.useChrome(hangingChrome());
        String jobId = ExportPortalTestSupport.jobIdOf(portal.post(base + "/api/export/pdf/start", "{\"project\":\"proj\",\"dir\":\"A\"}").body());
        long child = childPid();
        assertEquals("running", portal.stateOf(base, jobId));

        assertEquals(200, portal.post(base + "/api/import/stop", "{\"jobId\":\"" + jobId + "\"}").statusCode());

        assertEquals("stopped", portal.waitForState(base, jobId, "stopped", 10));
        assertTrue(isGone(child), "Chrome's child process is still running after Stop");
    }

    @Test
    void clear_whileTheJobRuns_isRefusedAndLeavesTheWorkDirectory() throws Exception {
        portal = new ExportPortalTestSupport(tempDir);
        String base = portal.startPortal();
        Set<Path> before = ExportPortalTestSupport.workDirectories();
        portal.useChrome(hangingChrome());
        String jobId = ExportPortalTestSupport.jobIdOf(portal.post(base + "/api/export/pdf/start", "{\"project\":\"proj\",\"dir\":\"A\"}").body());
        long child = childPid();
        Path work = ExportPortalTestSupport.newWorkDirectory(before);

        assertEquals(409, portal.post(base + "/api/import/clear", "{\"jobId\":\"" + jobId + "\"}").statusCode());
        assertTrue(Files.isDirectory(work), "a running job's work directory was deleted");

        portal.post(base + "/api/import/stop", "{\"jobId\":\"" + jobId + "\"}");
        assertTrue(isGone(child));
        portal.waitForState(base, jobId, "stopped", 10);
        portal.post(base + "/api/import/clear", "{\"jobId\":\"" + jobId + "\"}");
    }

    @Test
    void clear_afterAStoppedJob_deletesItsWorkDirectory() throws Exception {
        portal = new ExportPortalTestSupport(tempDir);
        String base = portal.startPortal();
        Set<Path> before = ExportPortalTestSupport.workDirectories();
        portal.useChrome(hangingChrome());
        String jobId = ExportPortalTestSupport.jobIdOf(portal.post(base + "/api/export/pdf/start", "{\"project\":\"proj\",\"dir\":\"A\"}").body());
        childPid();
        Path work = ExportPortalTestSupport.newWorkDirectory(before);
        portal.post(base + "/api/import/stop", "{\"jobId\":\"" + jobId + "\"}");
        assertEquals("stopped", portal.waitForState(base, jobId, "stopped", 10));

        assertEquals(200, portal.post(base + "/api/import/clear", "{\"jobId\":\"" + jobId + "\"}").statusCode());

        assertFalse(Files.exists(work), "the work directory of a stopped job is left");
    }

    @Test
    void clear_afterAJobThatEndedInError_deletesItsWorkDirectory() throws Exception {
        portal = new ExportPortalTestSupport(tempDir);
        String base = portal.startPortal();
        Set<Path> before = ExportPortalTestSupport.workDirectories();
        portal.useChrome(portal.script("silent-chrome.sh", "exit 0\n"));
        String jobId = ExportPortalTestSupport.jobIdOf(portal.post(base + "/api/export/pdf/start", "{\"project\":\"proj\",\"dir\":\"A\"}").body());
        assertEquals("error", portal.waitForState(base, jobId, "error", 20));
        Path work = ExportPortalTestSupport.newWorkDirectory(before);

        assertEquals(200, portal.post(base + "/api/import/clear", "{\"jobId\":\"" + jobId + "\"}").statusCode());

        assertFalse(Files.exists(work), "the work directory of a failed job is left");
    }

    @Test
    void download_afterAFinishedExport_sendsThePdf_andClearDeletesItWithItsWorkDirectory() throws Exception {
        portal = new ExportPortalTestSupport(tempDir);
        String base = portal.startPortal();
        Path fixture = tempDir.resolve("chrome-output.pdf");
        ExportPortalTestSupport.writeChromePdf(fixture);
        // A Chrome that writes the PDF to the place its --print-to-pdf argument names.
        portal.useChrome(portal.script("printing-chrome.sh",
                "for a in \"$@\"; do case \"$a\" in --print-to-pdf=*) out=\"${a#--print-to-pdf=}\";; esac; done\ncp '" + fixture + "' \"$out\"\n"));
        Set<Path> before = ExportPortalTestSupport.workDirectories();
        String jobId = ExportPortalTestSupport.jobIdOf(portal.post(base + "/api/export/pdf/start", "{\"project\":\"proj\",\"dir\":\"A\"}").body());
        assertEquals("done", portal.waitForState(base, jobId, "done", 30), portal.get(base + "/api/import/jobs").body());
        Path work = ExportPortalTestSupport.newWorkDirectory(before);

        HttpResponse<byte[]> download = portal.getBytes(base + "/api/export/download?jobId=" + jobId);
        assertEquals(200, download.statusCode());
        assertEquals("application/pdf", download.headers().firstValue("Content-Type").orElse(""));
        assertArrayEquals(new byte[] {'%', 'P', 'D', 'F', '-'}, java.util.Arrays.copyOf(download.body(), 5));
        assertTrue(download.headers().firstValue("Content-Disposition").orElse("").contains("proj-A.pdf"));
        assertEquals(Files.size(work.resolve("proj-A.pdf")), download.body().length, "the file is sent whole");

        assertEquals(200, portal.post(base + "/api/import/clear", "{\"jobId\":\"" + jobId + "\"}").statusCode());
        assertFalse(Files.exists(work), "the work directory of a finished job is left");
        assertEquals(404, portal.get(base + "/api/export/download?jobId=" + jobId).statusCode());
    }
}
