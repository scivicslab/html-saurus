package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The portal answers each request on a virtual thread, and many requests at once all get their answer. */
class PortalRequestThreadsTest {

    @TempDir
    Path tempDir;

    private HttpServer start() throws Exception {
        Path dir = tempDir.resolve("proj");
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(dir.resolve("docs/intro.md"), "---\ntitle: Introduction\n---\n\n# Introduction\n\nHello world.");
        BuildStages.build(dir.resolve("docs"), dir.resolve("static-html"), false);
        BuildStages.reindex(dir.resolve("docs"), dir.resolve("search-index"));
        return new PortalServer(tempDir, List.of(dir), 0, null, 0, BasePath.none(), null, false).start();
    }

    @Test
    void theExecutorRunsEachTaskOnAVirtualThread() throws Exception {
        HttpServer http = start();
        try {
            AtomicReference<Thread> ran = new AtomicReference<>();
            CompletableFuture<Void> done = new CompletableFuture<>();
            http.getExecutor().execute(() -> { ran.set(Thread.currentThread()); done.complete(null); });
            done.get(10, TimeUnit.SECONDS);
            assertTrue(ran.get().isVirtual());
        } finally {
            http.stop(0);
        }
    }

    @Test
    void ofManyRequestsAtOnce_everyOneIsAnswered() throws Exception {
        HttpServer http = start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            List<CompletableFuture<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                String path = i % 2 == 0 ? "/proj/intro/" : "/api/search?q=Hello";
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + http.getAddress().getPort() + path)).build();
                answers.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(HttpResponse::statusCode));
            }
            for (var a : answers) assertEquals(200, a.get(30, TimeUnit.SECONDS));
        } finally {
            http.stop(0);
        }
    }
}
