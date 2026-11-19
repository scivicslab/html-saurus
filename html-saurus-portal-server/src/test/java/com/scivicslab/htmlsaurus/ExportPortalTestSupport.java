package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A portal over a small project, and a stand-in for Chrome, for the tests that press Make PDF over HTTP
 * (ExportToPdf_261119_oo01).
 */
final class ExportPortalTestSupport {

    final HttpClient client = HttpClient.newHttpClient();
    final Path tempDir;
    private HttpServer http;
    /** The portal the tests talk to, for the places a test may plug a stand-in into. */
    PortalServer portalServer;
    private String previousChrome;

    ExportPortalTestSupport(Path tempDir) {
        this.tempDir = tempDir;
        this.previousChrome = System.getProperty(ChromePrinter.PROPERTY);
    }

    /** Starts a portal over a project {@code proj} whose directory {@code A} holds two documents; returns its base URL. */
    String startPortal() throws IOException {
        Path project = tempDir.resolve("proj");
        Files.createDirectories(project.resolve("docs/A"));
        Files.writeString(project.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(project.resolve("docs/A/one.md"), "---\ntitle: One\n---\n\nBody.\n");
        Files.writeString(project.resolve("docs/A/two.md"), "---\ntitle: Two\n---\n\nBody.\n");
        BuildStages.build(project.resolve("docs"), project.resolve("static-html"), false);
        portalServer = new PortalServer(tempDir, List.of(project), 0, null, 0);
        http = portalServer.start();
        return "http://localhost:" + http.getAddress().getPort();
    }

    /** Makes the portal use the script at {@code chrome} as its Chrome. */
    void useChrome(Path chrome) {
        System.setProperty(ChromePrinter.PROPERTY, chrome.toString());
    }

    /** Writes an executable script. */
    Path script(String name, String body) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    void close() {
        if (previousChrome == null) System.clearProperty(ChromePrinter.PROPERTY);
        else System.setProperty(ChromePrinter.PROPERTY, previousChrome);
        if (http != null) http.stop(0);
    }

    HttpResponse<String> post(String url, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<byte[]> getBytes(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** @return the id in an answer like {@code {"jobId":"..."}} */
    static String jobIdOf(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"jobId\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!m.find()) throw new IllegalStateException("No jobId in " + json);
        return m.group(1);
    }

    /** @return the state of a job as the Batch Job list names it ({@code running}, {@code done}, {@code error}, {@code stopped}) */
    String stateOf(String base, String jobId) throws Exception {
        String list = get(base + "/api/import/jobs").body();
        int at = list.indexOf("\"jobId\":\"" + jobId + "\"");
        if (at < 0) return "(not listed)";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"state\":\"([a-z]+)\"").matcher(list.substring(at));
        return m.find() ? m.group(1) : "(no state)";
    }

    /** @return what a job says it is doing, as the Batch Job list shows it */
    String phaseOf(String base, String jobId) throws Exception {
        String list = get(base + "/api/import/jobs").body();
        int at = list.indexOf("\"jobId\":\"" + jobId + "\"");
        if (at < 0) return "(not listed)";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"phase\":\"([^\"]*)\"").matcher(list.substring(at));
        return m.find() ? m.group(1) : "(no phase)";
    }

    /** Waits until a job is in the state, or the time is up; returns the state seen last. */
    String waitForState(String base, String jobId, String wanted, int seconds) throws Exception {
        String state = "";
        for (int i = 0; i < seconds * 20; i++) {
            state = stateOf(base, jobId);
            if (state.equals(wanted)) return state;
            Thread.sleep(50);
        }
        return state;
    }

    /** @return the export work directories that exist in the system's temporary directory */
    static Set<Path> workDirectories() throws IOException {
        Set<Path> found = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")),
                PdfExportJob.WORK_DIRECTORY_PREFIX + "*")) {
            for (Path p : stream) found.add(p);
        }
        return found;
    }

    /** @return the one work directory that was not there in {@code before} */
    static Path newWorkDirectory(Set<Path> before) throws IOException {
        Set<Path> now = workDirectories();
        now.removeAll(before);
        if (now.size() != 1) throw new IllegalStateException("Expected one new work directory, found " + now);
        return now.iterator().next();
    }

    /** Writes a PDF of the shape Chrome prints for directory {@code A}: a cover, a contents page and one page per document. */
    static void writeChromePdf(Path pdf) throws IOException {
        ExportTestPdfs.write(pdf, List.of("cover", "contents", "one", "two"), 2, Map.of(1, 3, 2, 4));
    }
}
