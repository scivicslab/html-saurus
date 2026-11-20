package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Export tab is for the development server only (ExportToPdf_261119_oo01). It starts Chrome on the machine and
 * rebuilds a project's HTML on a request that asks for no credential, so a public site must not have it. A running
 * production server answers none of its paths and shows no tab, and the classes that do this are not on its classpath.
 */
@Tag("ExportToPdf_261119_oo01")
class ExportNotInProductionTest {

    @TempDir
    Path tempDir;

    private HttpServer started;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @AfterEach
    void stopServer() {
        if (started != null) started.stop(0);
    }

    /** A production server on a port the system picks, over one built page. */
    private String startProductionServer() throws IOException {
        Path docs = tempDir.resolve("docs");
        Path staticDir = tempDir.resolve("static-html");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("intro.md"), "---\ntitle: Intro\nid: intro\n---\n\nHello.\n");
        BuildStages.build(docs, staticDir, true);
        Path indexDir = tempDir.resolve("search-index");
        BuildStages.reindex(docs, indexDir, "ja", true);
        started = new SearchServer(staticDir, indexDir, 0).start();
        return "http://localhost:" + started.getAddress().getPort();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("A running production server answers 404 for every export path")
    void exportPaths_onARunningProductionServer_areNotFound() throws Exception {
        String base = startProductionServer();
        assertEquals(404, send(HttpRequest.newBuilder(URI.create(base + "/api/export/dirs?project=doc_AASG1")).build()).statusCode());
        assertEquals(404, send(HttpRequest.newBuilder(URI.create(base + "/api/export/download?jobId=x")).build()).statusCode());
        assertEquals(404, send(HttpRequest.newBuilder(URI.create(base + "/api/export/pdf/start"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"project\":\"doc_AASG1\",\"dir\":\"\"}")).build()).statusCode());
    }

    @Test
    @DisplayName("A running production server's pages have no Export tab")
    void indexPage_onARunningProductionServer_hasNoExportTab() throws Exception {
        String base = startProductionServer();
        HttpResponse<String> index = send(HttpRequest.newBuilder(URI.create(base + "/")).build());
        assertFalse(index.body().contains("tab-btn-export") || index.body().contains("pane-option-export"),
                "the page of the production server has the Export menu item");
        assertFalse(index.body().contains("/api/export/"), "the page of the production server mentions the export API");
    }

    @Test
    @DisplayName("The export classes are not on the production server's classpath")
    void exportClasses_areNotOnTheProductionClasspath() {
        for (String name : List.of("PdfExportJob", "PdfExportHtml", "PdfExportPlan", "PdfExportFinish",
                                   "PdfExportDirectories", "ChromePrinter", "HtmlToPdfPrinter", "ExportOutcome")) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("com.scivicslab.htmlsaurus." + name),
                    name + " is on the production server's classpath, so it ships in its jar");
        }
    }
}
