package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the portal's log says about an export (ExportToPdf_261119_oo01). A press of Make PDF that the log does not
 * mention leaves nobody able to tell whether the request arrived, was refused, or failed later.
 */
@Tag("ExportToPdf_261119_oo01")
class ExportStartLoggingTest {

    @TempDir
    Path tempDir;

    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final Handler handler = new Handler() {
        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() { }
        @Override public void close() { }
    };
    private final Logger portalLogger = Logger.getLogger(PortalServer.class.getName());
    private final Logger jobLogger = Logger.getLogger(PdfExportJob.class.getName());
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer http;
    private String previousChrome;

    @BeforeEach
    void captureLogs() {
        portalLogger.addHandler(handler);
        jobLogger.addHandler(handler);
        previousChrome = System.getProperty(ChromePrinter.PROPERTY);
    }

    @AfterEach
    void restore() {
        portalLogger.removeHandler(handler);
        jobLogger.removeHandler(handler);
        if (previousChrome == null) System.clearProperty(ChromePrinter.PROPERTY);
        else System.setProperty(ChromePrinter.PROPERTY, previousChrome);
        if (http != null) http.stop(0);
    }

    private String startPortal() throws IOException {
        Path project = tempDir.resolve("proj");
        Files.createDirectories(project.resolve("docs/A"));
        Files.writeString(project.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(project.resolve("docs/A/one.md"), "---\ntitle: One\n---\n\nBody.\n");
        Files.writeString(project.resolve("docs/A/two.md"), "---\ntitle: Two\n---\n\nBody.\n");
        BuildStages.build(project.resolve("docs"), project.resolve("static-html"), false);
        http = new PortalServer(tempDir, List.of(project), 0, null, 0).start();
        return "http://localhost:" + http.getAddress().getPort();
    }

    private HttpResponse<String> start(String base, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/api/export/pdf/start"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private boolean logged(Level level, String... words) {
        synchronized (records) {
            return records.stream().anyMatch(r -> r.getLevel().equals(level)
                    && java.util.Arrays.stream(words).allMatch(w -> java.text.MessageFormat.format(r.getMessage(),
                            r.getParameters() == null ? new Object[0] : r.getParameters()).contains(w)));
        }
    }

    @Test
    void start_whenTheProjectIsUnknown_answers404AndLogsWhyNothingStarted() throws Exception {
        String base = startPortal();
        assertEquals(404, start(base, "{\"project\":\"nope\",\"dir\":\"\"}").statusCode());
        assertTrue(logged(Level.WARNING, "Export not started", "nope"), "log: " + records.stream().map(LogRecord::getMessage).toList());
    }

    @Test
    void start_whenTheDirectoryDoesNotExist_answers400AndLogsTheDirectory() throws Exception {
        String base = startPortal();
        assertEquals(400, start(base, "{\"project\":\"proj\",\"dir\":\"missing\"}").statusCode());
        assertTrue(logged(Level.WARNING, "Export not started", "missing"), "log: " + records.stream().map(LogRecord::getMessage).toList());
    }

    @Test
    void start_whenNoChromeIsFound_answers500AndLogsIt() throws Exception {
        String base = startPortal();
        System.setProperty(ChromePrinter.PROPERTY, tempDir.resolve("no-such-chrome").toString());
        assertEquals(500, start(base, "{\"project\":\"proj\",\"dir\":\"A\"}").statusCode());
        assertTrue(logged(Level.WARNING, "Export not started", "Chrome"), "log: " + records.stream().map(LogRecord::getMessage).toList());
    }

    @Test
    void start_whenTheExportIsAccepted_logsItsStart_andLogsTheFailureOfAJobThatEndsInError() throws Exception {
        String base = startPortal();
        // A Chrome that exits at once and writes no PDF: the job accepted by the request ends in error.
        Path chrome = tempDir.resolve("fake-chrome.sh");
        Files.writeString(chrome, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(chrome, PosixFilePermissions.fromString("rwxr-xr-x"));
        System.setProperty(ChromePrinter.PROPERTY, chrome.toString());

        assertEquals(202, start(base, "{\"project\":\"proj\",\"dir\":\"A\"}").statusCode());

        assertTrue(logged(Level.INFO, "Export started", "proj", "A"), "log: " + records.stream().map(LogRecord::getMessage).toList());
        for (int i = 0; i < 200 && !logged(Level.SEVERE, "Export failed"); i++) Thread.sleep(50);
        assertTrue(logged(Level.SEVERE, "Export failed", "proj"), "log: " + records.stream().map(LogRecord::getMessage).toList());
    }
}
