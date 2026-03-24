package com.scivicslab.yadoc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class PortalServer {

    record Project(String name, Path projectDir, Path staticDir, Path indexDir) {}

    private final List<Project> projects;
    private final Map<String, Project> projectMap;
    private final int port;

    public PortalServer(List<Path> projectDirs, int port) {
        this.port = port;
        this.projects = new ArrayList<>();
        this.projectMap = new LinkedHashMap<>();
        for (Path p : projectDirs) {
            String name = p.getFileName().toString();
            Project proj = new Project(name, p, p.resolve("static-html"), p.resolve("search-index"));
            projects.add(proj);
            projectMap.put(name, proj);
        }
    }

    public void start() throws IOException {
        var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handleAll);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("Portal at http://localhost:" + port);
        System.out.println("Press Ctrl+C to stop.");
    }

    // ---- Routing ------------------------------------------------

    private void handleAll(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();

        if (path.equals("/") || path.isEmpty()) {
            servePortalIndex(ex);
            return;
        }

        // Build API: POST /api/build/<project>
        if (path.startsWith("/api/build/")) {
            String name = path.substring("/api/build/".length());
            handleBuild(ex, name);
            return;
        }

        // Cross-project search API (JSON): GET /api/search?q=...
        if (path.equals("/api/search")) {
            handleGlobalSearch(ex);
            return;
        }

        // Cross-project search page (SSR): GET /search?q=...
        if (path.equals("/search")) {
            handleSearchPage(ex);
            return;
        }

        // Split: /<project>[/<rest>]
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        int slash = stripped.indexOf('/');
        String projectName = slash >= 0 ? stripped.substring(0, slash) : stripped;
        String rest = slash >= 0 ? stripped.substring(slash) : "/";

        Project proj = projectMap.get(projectName);
        if (proj == null) {
            respond(ex, 404, "text/plain", "Project not found: " + projectName);
            return;
        }

        // Redirect /<project> → /<project>/
        if (slash < 0) {
            ex.getResponseHeaders().set("Location", "/" + projectName + "/");
            ex.sendResponseHeaders(302, -1);
            return;
        }

        if (rest.equals("/") || rest.isEmpty()) rest = "/index.html";

        if (rest.startsWith("/search")) {
            handleSearch(ex, proj);
        } else {
            handleStatic(ex, proj, rest);
        }
    }

    // ---- Build API endpoint -------------------------------------

    private void handleBuild(HttpExchange ex, String name) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        Project proj = projectMap.get(name);
        if (proj == null) {
            respond(ex, 404, "application/json", "{\"error\":\"Project not found\"}");
            return;
        }
        long start = System.currentTimeMillis();
        System.out.println("Build requested: " + name);
        try {
            Main.build(proj.projectDir().resolve("docs"), proj.staticDir());
            Main.reindex(proj.projectDir().resolve("docs"), proj.indexDir());
            long elapsed = System.currentTimeMillis() - start;
            respond(ex, 200, "application/json",
                "{\"status\":\"ok\",\"project\":\"" + name + "\",\"ms\":" + elapsed + "}");
        } catch (Exception e) {
            System.err.println("Build error for " + name + ": " + e.getMessage());
            respond(ex, 500, "application/json",
                "{\"error\":" + jsonStr(e.getMessage()) + "}");
        }
    }

    // ---- Portal index page --------------------------------------

    private void servePortalIndex(HttpExchange ex) throws IOException {
        var sb = new StringBuilder();
        sb.append("""
            <!DOCTYPE html>
            <html lang="ja">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Documentation Portal</title>
              <link rel="icon" type="image/svg+xml" href="data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMjAwIiBoZWlnaHQ9IjIwMCIgdmlld0JveD0iMCAwIDIwMCAyMDAiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+PGcgZmlsbD0ibm9uZSIgZmlsbC1ydWxlPSJldmVub2RkIj48cGF0aCBmaWxsPSIjRkZGIiBkPSJNOTkgNTJoODR2MzRIOTl6Ii8+PHBhdGggZD0iTTIzIDE2M2MtNy4zOTggMC0xMy44NDMtNC4wMjctMTcuMzAzLTEwQTE5Ljg4NiAxOS44ODYgMCAwIDAgMyAxNjNjMCAxMS4wNDYgOC45NTQgMjAgMjAgMjBoMjB2LTIwSDIzeiIgZmlsbD0iIzhCNDUxMyIvPjxwYXRoIGQ9Ik0xMTIuOTggNTcuMzc2TDE4MyA1M1Y0M2MwLTExLjA0Ni04Ljk1NC0yMC0yMC0yMEg3M2wtMi41LTQuMzNjLTEuMTEyLTEuOTI1LTMuODg5LTEuOTI1LTUgMEw2MyAyM2wtMi41LTQuMzNjLTEuMTExLTEuOTI1LTMuODg5LTEuOTI1LTUgMEw1MyAyM2wtMi41LTQuMzNjLTEuMTExLTEuOTI1LTMuODg5LTEuOTI1LTUgMEw0MyAyM2MtLjAyMiAwLS4wNDIuMDAzLS4wNjUuMDAzbC00LjE0Mi00LjE0MWMtMS41Ny0xLjU3MS00LjI1Mi0uODUzLTQuODI4IDEuMjk0bC0xLjM2OSA1LjEwNC01LjE5Mi0xLjM5MmMtMi4xNDgtLjU3NS00LjExMSAxLjM4OS0zLjUzNSAzLjUzNmwxLjM5IDUuMTkzLTUuMTAyIDEuMzY3Yy0yLjE0OC41NzYtMi44NjcgMy4yNTktMS4yOTYgNC44M2w0LjE0MiA0LjE0MmMwIC4wMjEtLjAwMy4wNDItLjAwMy4wNjRsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgNTNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgNjNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgNzNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgODNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgOTNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgMTAzbC00LjMzIDIuNWMtMS45MjUgMS4xMTEtMS45MjUgMy44ODkgMCA1TDIzIDExM2wtNC4zMyAyLjVjLTEuOTI1IDEuMTExLTEuOTI1IDMuODg5IDAgNUwyMyAxMjNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgMTMzbC00LjMzIDIuNWMtMS45MjUgMS4xMTEtMS45MjUgMy44ODkgMCA1TDIzIDE0M2wtNC4zMyAyLjVjLTEuOTI1IDEuMTExLTEuOTI1IDMuODg5IDAgNUwyMyAxNTNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgMTYzYzAgMTEuMDQ2IDguOTU0IDIwIDIwIDIwaDEyMGMxMS4wNDYgMCAyMC04Ljk1NCAyMC0yMFY4M2wtNzAuMDItNC4zNzZBMTAuNjQ1IDEwLjY0NSAwIDAgMSAxMDMgNjhjMC01LjYyMSA0LjM3LTEwLjI3MyA5Ljk4LTEwLjYyNCIgZmlsbD0iIzhCNDUxMyIvPjxwYXRoIGZpbGw9IiM4QjQ1MTMiIGQ9Ik0xNDMgMTgzaDMwdi00MGgtMzB6Ii8+PHBhdGggZD0iTTE5MyAxNThjLS4yMTkgMC0uNDI4LjAzNy0uNjM5LjA2NC0uMDM4LS4xNS0uMDc0LS4zMDEtLjExNi0uNDUxQTUgNSAwIDAgMCAxOTAuMzIgMTQ4YTQuOTYgNC45NiAwIDAgMC0zLjAxNiAxLjAzNiAyNi41MzEgMjYuNTMxIDAgMCAwLS4zMzUtLjMzNiA0Ljk1NSA0Ljk1NSAwIDAgMCAxLjAxMS0yLjk4NyA1IDUgMCAwIDAtOS41OTktMS45NTljLS4xNDgtLjA0Mi0uMjk3LS4wNzctLjQ0NS0uMTE1LjAyNy0uMjExLjA2NC0uNDIuMDY0LS42MzlhNSA1IDAgMCAwLTUtNSA1IDUgMCAwIDAtNSA1YzAgLjIxOS4wMzcuNDI4LjA2NC42MzktLjE0OC4wMzgtLjI5Ny4wNzMtLjQ0NS4xMTVhNC45OTggNC45OTggMCAwIDAtOS41OTkgMS45NTljMCAxLjEyNS4zODQgMi4xNTEgMS4wMTEgMi45ODctMy43MTcgMy42MzItNi4wMzEgOC42OTMtNi4wMzEgMTQuMyAwIDExLjA0NiA4Ljk1NCAyMCAyMCAyMCA5LjMzOSAwIDE3LjE2LTYuNDEgMTkuMzYxLTE1LjA2NC4yMTEuMDI3LjQyLjA2NC42MzkuMDY0YTUgNSAwIDAgMCA1LTUgNSA1IDAgMCAwLTUtNSIgZmlsbD0iI0EwNTIyRCIvPjxwYXRoIGZpbGw9IiM4QjQ1MTMiIGQ9Ik0xNTMgMTIzaDMwdi0yMGgtMzB6Ii8+PHBhdGggZD0iTTE5MyAxMTUuNWEyLjUgMi41IDAgMSAwIDAtNWMtLjEwOSAwLS4yMTQuMDE5LS4zMTkuMDMyLS4wMi0uMDc1LS4wMzctLjE1LS4wNTgtLjIyNWEyLjUwMSAyLjUwMSAwIDAgMC0uOTYzLTQuODA3Yy0uNTY5IDAtMS4wODguMTk3LTEuNTA4LjUxOGE2LjY1MyA2LjY1MyAwIDAgMC0uMTY4LS4xNjhjLjMxNC0uNDE3LjUwNi0uOTMxLjUwNi0xLjQ5NGEyLjUgMi41IDAgMCAwLTQuOC0uOTc5QTkuOTg3IDkuOTg3IDAgMCAwIDE4MyAxMDNjLTUuNTIyIDAtMTAgNC40NzgtMTAgMTBzNC40NzggMTAgMTAgMTBjLjkzNCAwIDEuODMzLS4xMzggMi42OS0uMzc3YTIuNSAyLjUgMCAwIDAgNC44LS45NzljMC0uNTYzLS4xOTItMS4wNzctLjUwNi0xLjQ5NC4wNTctLjA1NS4xMTMtLjExMS4xNjgtLjE2OC40Mi4zMjEuOTM5LjUxOCAxLjUwOC41MThhMi41IDIuNSAwIDAgMCAuOTYzLTQuODA3Yy4wMjEtLjA3NC4wMzgtLjE1LjA1OC0uMjI1LjEwNS4wMTMuMjEuMDMyLjMxOS4wMzIiIGZpbGw9IiNBMDUyMkQiLz48cGF0aCBkPSJNNjMgNTUuNWEyLjUgMi41IDAgMCAxLTIuNS0yLjVjMC00LjEzNi0zLjM2NC03LjUtNy41LTcuNXMtNy41IDMuMzY0LTcuNSA3LjVhMi41IDIuNSAwIDEgMS01IDBjMC02Ljg5MyA1LjYwNy0xMi41IDEyLjUtMTIuNVM2NS41IDQ2LjEwNyA2NS41IDUzYTIuNSAyLjUgMCAwIDEtMi41IDIuNSIgZmlsbD0iIzAwMCIvPjxwYXRoIGQ9Ik0xMDMgMTgzaDYwYzExLjA0NiAwIDIwLTguOTU0IDIwLTIwVjkzaC02MGMtMTEuMDQ2IDAtMjAgOC45NTQtMjAgMjB2NzB6IiBmaWxsPSIjRkZGRjUwIi8+PHBhdGggZD0iTTE2OC4wMiAxMjRoLTUwLjA0YTEgMSAwIDEgMSAwLTJoNTAuMDRhMSAxIDAgMSAxIDAgMm0wIDIwaC01MC4wNGExIDEgMCAxIDEgMC0yaDUwLjA0YTEgMSAwIDEgMSAwIDJtMCAyMGgtNTAuMDRhMSAxIDAgMSAxIDAtMmg1MC4wNGExIDEgMCAxIDEgMCAybTAtNDkuODE0aC01MC4wNGExIDEgMCAxIDEgMC0yaDUwLjA0YTEgMSAwIDEgMSAwIDJtMCAxOS44MTRoLTUwLjA0YTEgMSAwIDEgMSAwLTJoNTAuMDRhMSAxIDAgMSAxIDAgMm0wIDIwaC01MC4wNGExIDEgMCAxIDEgMC0yaDUwLjA0YTEgMSAwIDEgMSAwIDJNMTgzIDYxLjYxMWMtLjAxMiAwLS4wMjItLjAwNi0uMDM0LS4wMDUtMy4wOS4xMDUtNC41NTIgMy4xOTYtNS44NDIgNS45MjMtMS4zNDYgMi44NS0yLjM4NyA0LjcwMy00LjA5MyA0LjY0Ny0xLjg4OS0uMDY4LTIuOTY5LTIuMjAyLTQuMTEzLTQuNDYtMS4zMTQtMi41OTQtMi44MTQtNS41MzYtNS45NjMtNS40MjYtMy4wNDYuMTA0LTQuNTEzIDIuNzk0LTUuODA3IDUuMTY3LTEuMzc3IDIuNTI4LTIuMzE0IDQuMDY1LTQuMTIxIDMuOTk0LTEuOTI3LS4wNy0yLjk1MS0xLjgwNS00LjEzNi0zLjgxMy0xLjMyMS0yLjIzNi0yLjg0OC00Ljc1LTUuOTM2LTQuNjY0LTIuOTk0LjEwMy00LjQ2NSAyLjM4NS01Ljc2MyA0LjQtMS4zNzMgMi4xMy0yLjMzNSAzLjQyOC00LjE2NSAzLjM1MS0xLjk3My0uMDctMi45OTItMS41MS00LjE3MS0zLjE3Ny0xLjMyNC0xLjg3My0yLjgxNi0zLjk5My01Ljg5NS0zLjg5LTIuOTI4LjEtNC4zOTkgMS45Ny01LjY5NiAzLjYxOC0xLjIzMiAxLjU2NC0yLjE5NCAyLjgwMi00LjIyOSAyLjcyNGExIDEgMCAwIDAtLjA3MiAyYzMuMDE3LjEwMSA0LjU0NS0xLjggNS44NzItMy40ODcgMS4xNzctMS40OTYgMi4xOTMtMi43ODcgNC4xOTMtMi44NTUgMS45MjYtLjA4MiAyLjgyOSAxLjExNSA0LjE5NSAzLjA0NSAxLjI5NyAxLjgzNCAyLjc2OSAzLjkxNCA1LjczMSA0LjAyMSAzLjEwMy4xMDQgNC41OTYtMi4yMTUgNS45MTgtNC4yNjcgMS4xODItMS44MzQgMi4yMDItMy40MTcgNC4xNS0zLjQ4NCAxLjc5My0uMDY3IDIuNzY5IDEuMzUgNC4xNDUgMy42ODEgMS4yOTcgMi4xOTcgMi43NjYgNC42ODYgNS43ODcgNC43OTYgMy4xMjUuMTA4IDQuNjM0LTIuNjIgNS45NDktNS4wMzUgMS4xMzktMi4wODggMi4yMTQtNC4wNiA0LjExOS00LjEyNiAxLjc5My0uMDQyIDIuNzI4IDEuNTk1IDQuMTExIDQuMzMgMS4yOTIgMi41NTMgMi43NTcgNS40NDUgNS44MjUgNS41NTZsLjE2OS4wMDNjMy4wNjQgMCA0LjUxOC0zLjA3NSA1LjgwNS01Ljc5NCAxLjEzOS0yLjQxIDIuMjE3LTQuNjggNC4wNjctNC43NzN2LTJ6IiBmaWxsPSIjMDAwIi8+PHBhdGggZmlsbD0iIzhCNDUxMyIgZD0iTTgzIDE4M2g0MHYtNDBIODN6Ii8+PHBhdGggZD0iTTE0MyAxNThjLS4yMTkgMC0uNDI4LjAzNy0uNjM5LjA2NC0uMDM4LS4xNS0uMDc0LS4zMDEtLjExNi0uNDUxQTUgNSAwIDAgMCAxNDAuMzIgMTQ4YTQuOTYgNC45NiAwIDAgMC0zLjAxNiAxLjAzNiAyNi41MzEgMjYuNTMxIDAgMCAwLS4zMzUtLjMzNiA0Ljk1NSA0Ljk1NSAwIDAgMCAxLjAxMS0yLjk4NyA1IDUgMCAwIDAtOS41OTktMS45NTljLS4xNDgtLjA0Mi0uMjk3LS4wNzctLjQ0NS0uMTE1LjAyNy0uMjExLjA2NC0uNDIuMDY0LS42MzlhNSA1IDAgMCAwLTUtNSA1IDUgMCAwIDAtNSA1YzAgLjIxOS4wMzcuNDI4LjA2NC42MzktLjE0OC4wMzgtLjI5Ny4wNzMtLjQ0NS4xMTVhNC45OTggNC45OTggMCAwIDAtOS41OTkgMS45NTljMCAxLjEyNS4zODQgMi4xNTEgMS4wMTEgMi45ODctMy43MTcgMy42MzItNi4wMzEgOC42OTMtNi4wMzEgMTQuMyAwIDExLjA0NiA4Ljk1NCAyMCAyMCAyMCA5LjMzOSAwIDE3LjE2LTYuNDEgMTkuMzYxLTE1LjA2NC4yMTEuMDI3LjQyLjA2NC42MzkuMDY0YTUgNSAwIDAgMCA1LTUgNSA1IDAgMCAwLTUtNSIgZmlsbD0iI0EwNTIyRCIvPjxwYXRoIGZpbGw9IiM4QjQ1MTMiIGQ9Ik04MyAxMjNoNDB2LTIwSDgzeiIvPjxwYXRoIGQ9Ik0xMzMgMTE1LjVhMi41IDIuNSAwIDEgMCAwLTVjLS4xMDkgMC0uMjE0LjAxOS0uMzE5LjAzMi0uMDItLjA3NS0uMDM3LS4xNS0uMDU4LS4yMjVhMi41MDEgMi41MDEgMCAwIDAtLjk2My00LjgwN2MtLjU2OSAwLTEuMDg4LjE5Ny0xLjUwOC41MThhNi42NTMgNi42NTMgMCAwIDAtLjE2OC0uMTY4Yy4zMTQtLjQxNy41MDYtLjkzMS41MDYtMS40OTRhMi41IDIuNSAwIDAgMC00LjgtLjk3OUE5Ljk4NyA5Ljk4NyAwIDAgMCAxMjMgMTAzYy01LjUyMiAwLTEwIDQuNDc4LTEwIDEwczQuNDc4IDEwIDEwIDEwYy45MzQgMCAxLjgzMy0uMTM4IDIuNjktLjM3N2EyLjUgMi41IDAgMCAwIDQuOC0uOTc5YzAtLjU2My0uMTkyLTEuMDc3LS41MDYtMS40OTQuMDU3LS4wNTUuMTEzLS4xMTEuMTY4LS4xNjguNDIuMzIxLjkzOS41MTggMS41MDguNTE4YTIuNSAyLjUgMCAwIDAgLjk2My00LjgwN2MuMDIxLS4wNzQuMDM4LS4xNS4wNTgtLjIyNS4xMDUuMDEzLjIxLjAzMi4zMTkuMDMyIiBmaWxsPSIjQTA1MjJEIi8+PHBhdGggZD0iTTE0MyA0MS43NWMtLjE2IDAtLjMzLS4wMi0uNDktLjA1YTIuNTIgMi41MiAwIDAgMS0uNDctLjE0Yy0uMTUtLjA2LS4yOS0uMTQtLjQzMS0uMjMtLjEzLS4wOS0uMjU5LS4yLS4zOC0uMzEtLjEwOS0uMTItLjIxOS0uMjQtLjMwOS0uMzhzLS4xNy0uMjgtLjIzMS0uNDNhMi42MTkgMi42MTkgMCAwIDEtLjE4OS0uOTZjMC0uMTYuMDItLjMzLjA1LS40OS4wMy0uMTYuMDgtLjMxLjEzOS0uNDcuMDYxLS4xNS4xNDEtLjI5LjIzMS0uNDMuMDktLjEzLjItLjI2LjMwOS0uMzguMTIxLS4xMS4yNS0uMjIuMzgtLjMxLjE0MS0uMDkuMjgxLS4xNy40MzEtLjIzLjE0OS0uMDYuMzEtLjExLjQ3LS4xNC4zMi0uMDcuNjUtLjA3Ljk4IDAgLjE1OS4wMy4zMi4wOC40Ny4xNC4xNDkuMDYuMjkuMTQuNDMuMjMuMTMuMDkuMjU5LjIuMzguMzEuMTEuMTIuMjIuMjUuMzEuMzguMDkuMTQuMTcuMjguMjMuNDMuMDYuMTYuMTEuMzEuMTQuNDcuMDI5LjE2LjA1LjMzLjA1LjQ5IDAgLjY2LS4yNzEgMS4zMS0uNzMgMS43Ny0uMTIxLjExLS4yNS4yMi0uMzguMzEtLjE0LjA5LS4yODEuMTctLjQzLjIzYTIuNTY1IDIuNTY1IDAgMCAxLS45Ni4xOW0yMC0xLjI1Yy0uNjYgMC0xLjMtLjI3LTEuNzcxLS43M2EzLjgwMiAzLjgwMiAwIDAgMS0uMzA5LS4zOGMtLjA5LS4xNC0uMTctLjI4LS4yMzEtLjQzYTIuNjE5IDIuNjE5IDAgMCAxLS4xODktLjk2YzAtLjY2LjI3LTEuMy43MjktMS43Ny4xMjEtLjExLjI1LS4yMi4zOC0uMzEuMTQxLS4wOS4yODEtLjE3LjQzMS0uMjMuMTQ5LS4wNi4zMS0uMTEuNDctLjE0LjMyLS4wNy42Ni0uMDcuOTggMCAuMTU5LjAzLjMyLjA4LjQ3LjE0LjE0OS4wNi4yOS4xNC40My4yMy4xMy4wOS4yNTkuMi4zOC4zMS40NTkuNDcuNzMgMS4xMS43MyAxLjc3IDAgLjE2LS4wMjEuMzMtLjA1LjQ5LS4wMy4xNi0uMDguMzItLjE0LjQ3LS4wNy4xNS0uMTQuMjktLjIzLjQzLS4wOS4xMy0uMi4yNi0uMzEuMzgtLjEyMS4xMS0uMjUuMjItLjM4LjMxLS4xNC4wOS0uMjgxLjE3LS40My4yM2EyLjU2NSAyLjU2NSAwIDAgMS0uOTYuMTkiIGZpbGw9IiMwMDAiLz48L2c+PC9zdmc+">
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                       background: #f6f7f8; min-height: 100vh; }
                header { background: #1c1e21; color: #fff; padding: 1rem 2rem;
                         display: flex; align-items: center; gap: 1.5rem; flex-wrap: wrap; }
                header h1 { font-size: 1.3rem; font-weight: 700; }
                header p { font-size: 0.85rem; color: #aaa; }
                form.portal-search { display: flex; gap: 0.5rem; margin-left: auto; }
                form.portal-search input[type=search] { padding: 0.35rem 0.8rem; border-radius: 4px;
                  border: 1px solid #666; background: rgba(255,255,255,0.12); color: #fff;
                  font-size: 0.875rem; width: 240px; outline: none; }
                form.portal-search input[type=search]::placeholder { color: #aaa; }
                form.portal-search input[type=search]:focus { background: rgba(255,255,255,0.22); border-color: #aaa; }
                form.portal-search button { padding: 0.35rem 0.9rem; border-radius: 4px; border: none;
                  background: #2e8555; color: #fff; font-weight: 600; cursor: pointer; font-size: 0.875rem; }
                form.portal-search button:hover { background: #267a4e; }
                main { max-width: 960px; margin: 2rem auto; padding: 0 1.5rem; }
                h2 { font-size: 1rem; color: #555; font-weight: 600;
                     text-transform: uppercase; letter-spacing: 0.05em;
                     margin-bottom: 1rem; border-bottom: 1px solid #ddd; padding-bottom: 0.5rem; }
                .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
                        gap: 1rem; }
                .card { background: #fff; border: 1px solid #e3e4e5; border-radius: 8px;
                        padding: 1.25rem 1.5rem; display: flex; flex-direction: column; gap: 0.6rem; }
                .card-name { font-size: 1rem; font-weight: 700; color: #1c1e21; }
                .card-path { font-size: 0.75rem; color: #999; font-family: monospace;
                             white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                .card-actions { display: flex; gap: 0.5rem; margin-top: 0.25rem; }
                .btn { padding: 0.35rem 0.9rem; border-radius: 5px; font-size: 0.82rem;
                       font-weight: 600; cursor: pointer; border: none; text-decoration: none;
                       display: inline-flex; align-items: center; }
                .btn-open { background: #2e8555; color: #fff; }
                .btn-open:hover { background: #267a4e; }
                .btn-build { background: #e8eaf0; color: #333; }
                .btn-build:hover { background: #d5d8e0; }
                .btn-build:disabled { opacity: 0.5; cursor: not-allowed; }
                .build-status { font-size: 0.75rem; color: #888; align-self: center; }
              </style>
            </head>
            <body>
            <header>
              <div>
                <h1>Documentation Portal</h1>
                <p>%d project(s)</p>
              </div>
              <form class="portal-search" action="/search" method="get">
                <input type="search" name="q" placeholder="Search all docs...">
                <button type="submit">Search</button>
              </form>
            </header>
            <main>
              <h2>Projects</h2>
              <div class="grid">
            """.formatted(projects.size()));

        for (Project p : projects) {
            sb.append("    <div class=\"card\">\n");
            sb.append("      <div class=\"card-name\">").append(escHtml(p.name())).append("</div>\n");
            sb.append("      <div class=\"card-path\">").append(escHtml(p.projectDir().toString())).append("</div>\n");
            sb.append("      <div class=\"card-actions\">\n");
            sb.append("        <a class=\"btn btn-open\" href=\"/").append(escHtml(p.name())).append("/\">Open</a>\n");
            sb.append("        <button class=\"btn btn-build\" onclick=\"doBuild('").append(escHtml(p.name())).append("', this)\">Build</button>\n");
            sb.append("        <span class=\"build-status\" id=\"status-").append(escHtml(p.name())).append("\"></span>\n");
            sb.append("      </div>\n");
            sb.append("    </div>\n");
        }

        sb.append("""
              </div>
            </main>
            <script>
            async function doBuild(name, btn) {
              const status = document.getElementById('status-' + name);
              btn.disabled = true;
              btn.textContent = 'Building...';
              status.textContent = '';
              try {
                const r = await fetch('/api/build/' + encodeURIComponent(name), {method: 'POST'});
                const j = await r.json();
                if (j.status === 'ok') {
                  status.textContent = 'Done (' + j.ms + 'ms)';
                  status.style.color = '#2e8555';
                } else {
                  status.textContent = 'Error: ' + (j.error || 'unknown');
                  status.style.color = '#e13238';
                }
              } catch (e) {
                status.textContent = 'Error: ' + e.message;
                status.style.color = '#e13238';
              }
              btn.disabled = false;
              btn.textContent = 'Build';
            }
            </script>
            </body>
            </html>
            """);

        respond(ex, 200, "text/html; charset=UTF-8", sb.toString());
    }

    // ---- Search results page (SSR) ------------------------------

    private static final int PAGE_SIZE = 100;

    private void handleSearchPage(HttpExchange ex) throws IOException {
        String q = queryParam(ex, "q");
        String pageStr = queryParam(ex, "page");
        int page = 1;
        try { if (!pageStr.isBlank()) page = Math.max(1, Integer.parseInt(pageStr)); }
        catch (NumberFormatException ignored) {}

        List<Map<String, String>> allHits = q.isBlank() ? List.of() : globalSearch(q);
        int total = allHits.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, totalPages);
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, total);
        List<Map<String, String>> hits = allHits.subList(from, to);

        var sb = new StringBuilder();
        sb.append("""
            <!DOCTYPE html>
            <html lang="ja">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Search: %s</title>
              <link rel="icon" type="image/svg+xml" href="data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMjAwIiBoZWlnaHQ9IjIwMCIgdmlld0JveD0iMCAwIDIwMCAyMDAiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+PGcgZmlsbD0ibm9uZSIgZmlsbC1ydWxlPSJldmVub2RkIj48cGF0aCBmaWxsPSIjRkZGIiBkPSJNOTkgNTJoODR2MzRIOTl6Ii8+PHBhdGggZD0iTTIzIDE2M2MtNy4zOTggMC0xMy44NDMtNC4wMjctMTcuMzAzLTEwQTE5Ljg4NiAxOS44ODYgMCAwIDAgMyAxNjNjMCAxMS4wNDYgOC45NTQgMjAgMjAgMjBoMjB2LTIwSDIzeiIgZmlsbD0iIzhCNDUxMyIvPjxwYXRoIGQ9Ik0xMTIuOTggNTcuMzc2TDE4MyA1M1Y0M2MwLTExLjA0Ni04Ljk1NC0yMC0yMC0yMEg3M2wtMi41LTQuMzNjLTEuMTEyLTEuOTI1LTMuODg5LTEuOTI1LTUgMEw2MyAyM2wtMi41LTQuMzNjLTEuMTExLTEuOTI1LTMuODg5LTEuOTI1LTUgMEw1MyAyM2wtMi41LTQuMzNjLTEuMTExLTEuOTI1LTMuODg5LTEuOTI1LTUgMEw0MyAyM2MtLjAyMiAwLS4wNDIuMDAzLS4wNjUuMDAzbC00LjE0Mi00LjE0MWMtMS41Ny0xLjU3MS00LjI1Mi0uODUzLTQuODI4IDEuMjk0bC0xLjM2OSA1LjEwNC01LjE5Mi0xLjM5MmMtMi4xNDgtLjU3NS00LjExMSAxLjM4OS0zLjUzNSAzLjUzNmwxLjM5IDUuMTkzLTUuMTAyIDEuMzY3Yy0yLjE0OC41NzYtMi44NjcgMy4yNTktMS4yOTYgNC44M2w0LjE0MiA0LjE0MmMwIC4wMjEtLjAwMy4wNDItLjAwMy4wNjRsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgNTNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgNjNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgNzNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgODNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgOTNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgMTAzbC00LjMzIDIuNWMtMS45MjUgMS4xMTEtMS45MjUgMy44ODkgMCA1TDIzIDExM2wtNC4zMyAyLjVjLTEuOTI1IDEuMTExLTEuOTI1IDMuODg5IDAgNUwyMyAxMjNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgMTMzbC00LjMzIDIuNWMtMS45MjUgMS4xMTEtMS45MjUgMy44ODkgMCA1TDIzIDE0M2wtNC4zMyAyLjVjLTEuOTI1IDEuMTExLTEuOTI1IDMuODg5IDAgNUwyMyAxNTNsLTQuMzMgMi41Yy0xLjkyNSAxLjExMS0xLjkyNSAzLjg4OSAwIDVMMjMgMTYzYzAgMTEuMDQ2IDguOTU0IDIwIDIwIDIwaDEyMGMxMS4wNDYgMCAyMC04Ljk1NCAyMC0yMFY4M2wtNzAuMDItNC4zNzZBMTAuNjQ1IDEwLjY0NSAwIDAgMSAxMDMgNjhjMC01LjYyMSA0LjM3LTEwLjI3MyA5Ljk4LTEwLjYyNCIgZmlsbD0iIzhCNDUxMyIvPjxwYXRoIGZpbGw9IiM4QjQ1MTMiIGQ9Ik0xNDMgMTgzaDMwdi00MGgtMzB6Ii8+PHBhdGggZD0iTTE5MyAxNThjLS4yMTkgMC0uNDI4LjAzNy0uNjM5LjA2NC0uMDM4LS4xNS0uMDc0LS4zMDEtLjExNi0uNDUxQTUgNSAwIDAgMCAxOTAuMzIgMTQ4YTQuOTYgNC45NiAwIDAgMC0zLjAxNiAxLjAzNiAyNi41MzEgMjYuNTMxIDAgMCAwLS4zMzUtLjMzNiA0Ljk1NSA0Ljk1NSAwIDAgMCAxLjAxMS0yLjk4NyA1IDUgMCAwIDAtOS41OTktMS45NTljLS4xNDgtLjA0Mi0uMjk3LS4wNzctLjQ0NS0uMTE1LjAyNy0uMjExLjA2NC0uNDIuMDY0LS42MzlhNSA1IDAgMCAwLTUtNSA1IDUgMCAwIDAtNSA1YzAgLjIxOS4wMzcuNDI4LjA2NC42MzktLjE0OC4wMzgtLjI5Ny4wNzMtLjQ0NS4xMTVhNC45OTggNC45OTggMCAwIDAtOS41OTkgMS45NTljMCAxLjEyNS4zODQgMi4xNTEgMS4wMTEgMi45ODctMy43MTcgMy42MzItNi4wMzEgOC42OTMtNi4wMzEgMTQuMyAwIDExLjA0NiA4Ljk1NCAyMCAyMCAyMCA5LjMzOSAwIDE3LjE2LTYuNDEgMTkuMzYxLTE1LjA2NC4yMTEuMDI3LjQyLjA2NC42MzkuMDY0YTUgNSAwIDAgMCA1LTUgNSA1IDAgMCAwLTUtNSIgZmlsbD0iI0EwNTIyRCIvPjxwYXRoIGZpbGw9IiM4QjQ1MTMiIGQ9Ik0xNTMgMTIzaDMwdi0yMGgtMzB6Ii8+PHBhdGggZD0iTTE5MyAxMTUuNWEyLjUgMi41IDAgMSAwIDAtNWMtLjEwOSAwLS4yMTQuMDE5LS4zMTkuMDMyLS4wMi0uMDc1LS4wMzctLjE1LS4wNTgtLjIyNWEyLjUwMSAyLjUwMSAwIDAgMC0uOTYzLTQuODA3Yy0uNTY5IDAtMS4wODguMTk3LTEuNTA4LjUxOGE2LjY1MyA2LjY1MyAwIDAgMC0uMTY4LS4xNjhjLjMxNC0uNDE3LjUwNi0uOTMxLjUwNi0xLjQ5NGEyLjUgMi41IDAgMCAwLTQuOC0uOTc5QTkuOTg3IDkuOTg3IDAgMCAwIDE4MyAxMDNjLTUuNTIyIDAtMTAgNC40NzgtMTAgMTBzNC40NzggMTAgMTAgMTBjLjkzNCAwIDEuODMzLS4xMzggMi42OS0uMzc3YTIuNSAyLjUgMCAwIDAgNC44LS45NzljMC0uNTYzLS4xOTItMS4wNzctLjUwNi0xLjQ5NC4wNTctLjA1NS4xMTMtLjExMS4xNjgtLjE2OC40Mi4zMjEuOTM5LjUxOCAxLjUwOC41MThhMi41IDIuNSAwIDAgMCAuOTYzLTQuODA3Yy4wMjEtLjA3NC4wMzgtLjE1LjA1OC0uMjI1LjEwNS4wMTMuMjEuMDMyLjMxOS4wMzIiIGZpbGw9IiNBMDUyMkQiLz48cGF0aCBkPSJNNjMgNTUuNWEyLjUgMi41IDAgMCAxLTIuNS0yLjVjMC00LjEzNi0zLjM2NC03LjUtNy41LTcuNXMtNy41IDMuMzY0LTcuNSA3LjVhMi41IDIuNSAwIDEgMS01IDBjMC02Ljg5MyA1LjYwNy0xMi41IDEyLjUtMTIuNVM2NS41IDQ2LjEwNyA2NS41IDUzYTIuNSAyLjUgMCAwIDEtMi41IDIuNSIgZmlsbD0iIzAwMCIvPjxwYXRoIGQ9Ik0xMDMgMTgzaDYwYzExLjA0NiAwIDIwLTguOTU0IDIwLTIwVjkzaC02MGMtMTEuMDQ2IDAtMjAgOC45NTQtMjAgMjB2NzB6IiBmaWxsPSIjRkZGRjUwIi8+PHBhdGggZD0iTTE2OC4wMiAxMjRoLTUwLjA0YTEgMSAwIDEgMSAwLTJoNTAuMDRhMSAxIDAgMSAxIDAgMm0wIDIwaC01MC4wNGExIDEgMCAxIDEgMC0yaDUwLjA0YTEgMSAwIDEgMSAwIDJtMCAyMGgtNTAuMDRhMSAxIDAgMSAxIDAtMmg1MC4wNGExIDEgMCAxIDEgMCAybTAtNDkuODE0aC01MC4wNGExIDEgMCAxIDEgMC0yaDUwLjA0YTEgMSAwIDEgMSAwIDJtMCAxOS44MTRoLTUwLjA0YTEgMSAwIDEgMSAwLTJoNTAuMDRhMSAxIDAgMSAxIDAgMm0wIDIwaC01MC4wNGExIDEgMCAxIDEgMC0yaDUwLjA0YTEgMSAwIDEgMSAwIDJNMTgzIDYxLjYxMWMtLjAxMiAwLS4wMjItLjAwNi0uMDM0LS4wMDUtMy4wOS4xMDUtNC41NTIgMy4xOTYtNS44NDIgNS45MjMtMS4zNDYgMi44NS0yLjM4NyA0LjcwMy00LjA5MyA0LjY0Ny0xLjg4OS0uMDY4LTIuOTY5LTIuMjAyLTQuMTEzLTQuNDYtMS4zMTQtMi41OTQtMi44MTQtNS41MzYtNS45NjMtNS40MjYtMy4wNDYuMTA0LTQuNTEzIDIuNzk0LTUuODA3IDUuMTY3LTEuMzc3IDIuNTI4LTIuMzE0IDQuMDY1LTQuMTIxIDMuOTk0LTEuOTI3LS4wNy0yLjk1MS0xLjgwNS00LjEzNi0zLjgxMy0xLjMyMS0yLjIzNi0yLjg0OC00Ljc1LTUuOTM2LTQuNjY0LTIuOTk0LjEwMy00LjQ2NSAyLjM4NS01Ljc2MyA0LjQtMS4zNzMgMi4xMy0yLjMzNSAzLjQyOC00LjE2NSAzLjM1MS0xLjk3My0uMDctMi45OTItMS41MS00LjE3MS0zLjE3Ny0xLjMyNC0xLjg3My0yLjgxNi0zLjk5My01Ljg5NS0zLjg5LTIuOTI4LjEtNC4zOTkgMS45Ny01LjY5NiAzLjYxOC0xLjIzMiAxLjU2NC0yLjE5NCAyLjgwMi00LjIyOSAyLjcyNGExIDEgMCAwIDAtLjA3MiAyYzMuMDE3LjEwMSA0LjU0NS0xLjggNS44NzItMy40ODcgMS4xNzctMS40OTYgMi4xOTMtMi43ODcgNC4xOTMtMi44NTUgMS45MjYtLjA4MiAyLjgyOSAxLjExNSA0LjE5NSAzLjA0NSAxLjI5NyAxLjgzNCAyLjc2OSAzLjkxNCA1LjczMSA0LjAyMSAzLjEwMy4xMDQgNC41OTYtMi4yMTUgNS45MTgtNC4yNjcgMS4xODItMS44MzQgMi4yMDItMy40MTcgNC4xNS0zLjQ4NCAxLjc5My0uMDY3IDIuNzY5IDEuMzUgNC4xNDUgMy42ODEgMS4yOTcgMi4xOTcgMi43NjYgNC42ODYgNS43ODcgNC43OTYgMy4xMjUuMTA4IDQuNjM0LTIuNjIgNS45NDktNS4wMzUgMS4xMzktMi4wODggMi4yMTQtNC4wNiA0LjExOS00LjEyNiAxLjc5My0uMDQyIDIuNzI4IDEuNTk1IDQuMTExIDQuMzMgMS4yOTIgMi41NTMgMi43NTcgNS40NDUgNS44MjUgNS41NTZsLjE2OS4wMDNjMy4wNjQgMCA0LjUxOC0zLjA3NSA1LjgwNS01Ljc5NCAxLjEzOS0yLjQxIDIuMjE3LTQuNjggNC4wNjctNC43NzN2LTJ6IiBmaWxsPSIjMDAwIi8+PHBhdGggZmlsbD0iIzhCNDUxMyIgZD0iTTgzIDE4M2g0MHYtNDBIODN6Ii8+PHBhdGggZD0iTTE0MyAxNThjLS4yMTkgMC0uNDI4LjAzNy0uNjM5LjA2NC0uMDM4LS4xNS0uMDc0LS4zMDEtLjExNi0uNDUxQTUgNSAwIDAgMCAxNDAuMzIgMTQ4YTQuOTYgNC45NiAwIDAgMC0zLjAxNiAxLjAzNiAyNi41MzEgMjYuNTMxIDAgMCAwLS4zMzUtLjMzNiA0Ljk1NSA0Ljk1NSAwIDAgMCAxLjAxMS0yLjk4NyA1IDUgMCAwIDAtOS41OTktMS45NTljLS4xNDgtLjA0Mi0uMjk3LS4wNzctLjQ0NS0uMTE1LjAyNy0uMjExLjA2NC0uNDIuMDY0LS42MzlhNSA1IDAgMCAwLTUtNSA1IDUgMCAwIDAtNSA1YzAgLjIxOS4wMzcuNDI4LjA2NC42MzktLjE0OC4wMzgtLjI5Ny4wNzMtLjQ0NS4xMTVhNC45OTggNC45OTggMCAwIDAtOS41OTkgMS45NTljMCAxLjEyNS4zODQgMi4xNTEgMS4wMTEgMi45ODctMy43MTcgMy42MzItNi4wMzEgOC42OTMtNi4wMzEgMTQuMyAwIDExLjA0NiA4Ljk1NCAyMCAyMCAyMCA5LjMzOSAwIDE3LjE2LTYuNDEgMTkuMzYxLTE1LjA2NC4yMTEuMDI3LjQyLjA2NC42MzkuMDY0YTUgNSAwIDAgMCA1LTUgNSA1IDAgMCAwLTUtNSIgZmlsbD0iI0EwNTIyRCIvPjxwYXRoIGZpbGw9IiM4QjQ1MTMiIGQ9Ik04MyAxMjNoNDB2LTIwSDgzeiIvPjxwYXRoIGQ9Ik0xMzMgMTE1LjVhMi41IDIuNSAwIDEgMCAwLTVjLS4xMDkgMC0uMjE0LjAxOS0uMzE5LjAzMi0uMDItLjA3NS0uMDM3LS4xNS0uMDU4LS4yMjVhMi41MDEgMi41MDEgMCAwIDAtLjk2My00LjgwN2MtLjU2OSAwLTEuMDg4LjE5Ny0xLjUwOC41MThhNi42NTMgNi42NTMgMCAwIDAtLjE2OC0uMTY4Yy4zMTQtLjQxNy41MDYtLjkzMS41MDYtMS40OTRhMi41IDIuNSAwIDAgMC00LjgtLjk3OUE5Ljk4NyA5Ljk4NyAwIDAgMCAxMjMgMTAzYy01LjUyMiAwLTEwIDQuNDc4LTEwIDEwczQuNDc4IDEwIDEwIDEwYy45MzQgMCAxLjgzMy0uMTM4IDIuNjktLjM3N2EyLjUgMi41IDAgMCAwIDQuOC0uOTc5YzAtLjU2My0uMTkyLTEuMDc3LS41MDYtMS40OTQuMDU3LS4wNTUuMTEzLS4xMTEuMTY4LS4xNjguNDIuMzIxLjkzOS41MTggMS41MDguNTE4YTIuNSAyLjUgMCAwIDAgLjk2My00LjgwN2MuMDIxLS4wNzQuMDM4LS4xNS4wNTgtLjIyNS4xMDUuMDEzLjIxLjAzMi4zMTkuMDMyIiBmaWxsPSIjQTA1MjJEIi8+PHBhdGggZD0iTTE0MyA0MS43NWMtLjE2IDAtLjMzLS4wMi0uNDktLjA1YTIuNTIgMi41MiAwIDAgMS0uNDctLjE0Yy0uMTUtLjA2LS4yOS0uMTQtLjQzMS0uMjMtLjEzLS4wOS0uMjU5LS4yLS4zOC0uMzEtLjEwOS0uMTItLjIxOS0uMjQtLjMwOS0uMzhzLS4xNy0uMjgtLjIzMS0uNDNhMi42MTkgMi42MTkgMCAwIDEtLjE4OS0uOTZjMC0uMTYuMDItLjMzLjA1LS40OS4wMy0uMTYuMDgtLjMxLjEzOS0uNDcuMDYxLS4xNS4xNDEtLjI5LjIzMS0uNDMuMDktLjEzLjItLjI2LjMwOS0uMzguMTIxLS4xMS4yNS0uMjIuMzgtLjMxLjE0MS0uMDkuMjgxLS4xNy40MzEtLjIzLjE0OS0uMDYuMzEtLjExLjQ3LS4xNC4zMi0uMDcuNjUtLjA3Ljk4IDAgLjE1OS4wMy4zMi4wOC40Ny4xNC4xNDkuMDYuMjkuMTQuNDMuMjMuMTMuMDkuMjU5LjIuMzguMzEuMTEuMTIuMjIuMjUuMzEuMzguMDkuMTQuMTcuMjguMjMuNDMuMDYuMTYuMTEuMzEuMTQuNDcuMDI5LjE2LjA1LjMzLjA1LjQ5IDAgLjY2LS4yNzEgMS4zMS0uNzMgMS43Ny0uMTIxLjExLS4yNS4yMi0uMzguMzEtLjE0LjA5LS4yODEuMTctLjQzLjIzYTIuNTY1IDIuNTY1IDAgMCAxLS45Ni4xOW0yMC0xLjI1Yy0uNjYgMC0xLjMtLjI3LTEuNzcxLS43M2EzLjgwMiAzLjgwMiAwIDAgMS0uMzA5LS4zOGMtLjA5LS4xNC0uMTctLjI4LS4yMzEtLjQzYTIuNjE5IDIuNjE5IDAgMCAxLS4xODktLjk2YzAtLjY2LjI3LTEuMy43MjktMS43Ny4xMjEtLjExLjI1LS4yMi4zOC0uMzEuMTQxLS4wOS4yODEtLjE3LjQzMS0uMjMuMTQ5LS4wNi4zMS0uMTEuNDctLjE0LjMyLS4wNy42Ni0uMDcuOTggMCAuMTU5LjAzLjMyLjA4LjQ3LjE0LjE0OS4wNi4yOS4xNC40My4yMy4xMy4wOS4yNTkuMi4zOC4zMS40NTkuNDcuNzMgMS4xMS43MyAxLjc3IDAgLjE2LS4wMjEuMzMtLjA1LjQ5LS4wMy4xNi0uMDguMzItLjE0LjQ3LS4wNy4xNS0uMTQuMjktLjIzLjQzLS4wOS4xMy0uMi4yNi0uMzEuMzgtLjEyMS4xMS0uMjUuMjItLjM4LjMxLS4xNC4wOS0uMjgxLjE3LS40My4yM2EyLjU2NSAyLjU2NSAwIDAgMS0uOTYuMTkiIGZpbGw9IiMwMDAiLz48L2c+PC9zdmc+">
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                       background: #f6f7f8; min-height: 100vh; }
                header { background: #1c1e21; color: #fff; padding: 1rem 2rem;
                         display: flex; align-items: center; gap: 1.5rem; }
                header a.home { color: #fff; text-decoration: none; font-weight: 700; font-size: 1.1rem;
                                white-space: nowrap; }
                header a.home:hover { color: #aaa; }
                form { display: flex; gap: 0.5rem; flex: 1; max-width: 600px; }
                form input[type=search] { flex: 1; padding: 0.4rem 0.8rem; border-radius: 4px;
                  border: 1px solid #666; background: rgba(255,255,255,0.12); color: #fff;
                  font-size: 0.9rem; outline: none; }
                form input[type=search]::placeholder { color: #aaa; }
                form input[type=search]:focus { background: rgba(255,255,255,0.22); border-color: #aaa; }
                form button { padding: 0.4rem 1rem; border-radius: 4px; border: none;
                  background: #2e8555; color: #fff; font-weight: 600; cursor: pointer; font-size: 0.9rem; }
                form button:hover { background: #267a4e; }
                main { max-width: 860px; margin: 2rem auto; padding: 0 1.5rem; }
                .result-count { color: #666; font-size: 0.875rem; margin-bottom: 1.5rem; }
                .result-count strong { color: #1c1e21; }
                .result { background: #fff; border: 1px solid #e3e4e5; border-radius: 8px;
                          padding: 1rem 1.25rem; margin-bottom: 0.75rem;
                          text-decoration: none; display: block; color: inherit;
                          transition: box-shadow 0.15s, border-color 0.15s; }
                .result:hover { box-shadow: 0 3px 10px rgba(0,0,0,0.08); border-color: #2e8555; }
                .result-project { font-size: 0.72rem; font-weight: 700; color: #2e8555;
                                  text-transform: uppercase; letter-spacing: 0.05em; }
                .result-title { font-size: 1rem; font-weight: 600; color: #1c1e21; margin: 0.2rem 0; }
                .result-breadcrumb { font-size: 0.75rem; color: #2e8555; margin-bottom: 0.3rem; }
                .result-summary { font-size: 0.82rem; color: #666; line-height: 1.5; }
                .no-results { color: #888; text-align: center; padding: 3rem; }
                .pager { margin-top: 2rem; display: flex; gap: 0.4rem; flex-wrap: wrap; align-items: center; }
                .pager a { padding: 0.3rem 0.7rem; border: 1px solid #ddd; border-radius: 4px;
                           text-decoration: none; color: #2e8555; font-size: 0.875rem; }
                .pager a:hover { background: #e8f4ee; border-color: #2e8555; }
                .pager-cur { padding: 0.3rem 0.7rem; border: 1px solid #2e8555; border-radius: 4px;
                             background: #2e8555; color: #fff; font-size: 0.875rem; font-weight: 600; }
              </style>
            </head>
            <body>
            <header>
              <a class="home" href="/">Documentation Portal</a>
              <form action="/search" method="get">
                <input type="search" name="q" value="%s" placeholder="Search all docs..." autofocus>
                <button type="submit">Search</button>
              </form>
            </header>
            <main>
            """.formatted(escHtml(q), escHtml(q)));

        if (q.isBlank()) {
            sb.append("<p class=\"no-results\">Please enter a search query.</p>\n");
        } else {
            sb.append("<p class=\"result-count\"><strong>").append(total)
              .append("</strong> result(s) for &ldquo;").append(escHtml(q)).append("&rdquo;");
            if (totalPages > 1)
                sb.append(" &mdash; page ").append(page).append(" / ").append(totalPages);
            sb.append("</p>\n");
            if (total == 0) {
                sb.append("<p class=\"no-results\">No results found.</p>\n");
            } else {
                for (var hit : hits) {
                    String href = "/" + hit.get("project") + hit.get("pagePath");
                    sb.append("<a class=\"result\" href=\"").append(escHtml(href)).append("\">\n");
                    sb.append("  <div class=\"result-project\">").append(escHtml(hit.get("project"))).append("</div>\n");
                    sb.append("  <div class=\"result-title\">").append(escHtml(hit.get("title"))).append("</div>\n");
                    sb.append("  <div class=\"result-breadcrumb\">").append(escHtml(pathToBreadcrumb(hit.get("pagePath")))).append("</div>\n");
                    sb.append("  <div class=\"result-summary\">").append(escHtml(hit.get("summary"))).append("</div>\n");
                    sb.append("</a>\n");
                }
                // Pager
                if (totalPages > 1) {
                    sb.append("<div class=\"pager\">");
                    String qEnc = java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8);
                    if (page > 1)
                        sb.append("<a href=\"/search?q=").append(qEnc).append("&page=").append(page - 1).append("\">&laquo; Prev</a> ");
                    for (int p = 1; p <= totalPages; p++) {
                        if (p == page)
                            sb.append("<span class=\"pager-cur\">").append(p).append("</span> ");
                        else
                            sb.append("<a href=\"/search?q=").append(qEnc).append("&page=").append(p).append("\">").append(p).append("</a> ");
                    }
                    if (page < totalPages)
                        sb.append("<a href=\"/search?q=").append(qEnc).append("&page=").append(page + 1).append("\">Next &raquo;</a>");
                    sb.append("</div>\n");
                }
            }
        }
        sb.append("</main>\n</body>\n</html>\n");
        respond(ex, 200, "text/html; charset=UTF-8", sb.toString());
    }

    // ---- Cross-project search -----------------------------------

    private void handleGlobalSearch(HttpExchange ex) throws IOException {
        String q = queryParam(ex, "q");
        List<Map<String, String>> hits = globalSearch(q);
        var sb = new StringBuilder("[");
        boolean first = true;
        for (var hit : hits) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{")
              .append("\"title\":").append(jsonStr(hit.get("title"))).append(",")
              .append("\"project\":").append(jsonStr(hit.get("project"))).append(",")
              .append("\"pagePath\":").append(jsonStr(hit.get("pagePath"))).append(",")
              .append("\"summary\":").append(jsonStr(hit.get("summary")))
              .append("}");
        }
        sb.append("]");
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    // Returns merged search results across all projects
    private List<Map<String, String>> globalSearch(String q) {
        var results = new ArrayList<Map<String, String>>();
        if (q.isBlank()) return results;
        for (Project proj : projects) {
            if (!Files.exists(proj.indexDir())) continue;
            collectHits(q, proj, results);
        }
        return results;
    }

    private void collectHits(String queryStr, Project proj, List<Map<String, String>> out) {
        try (var dir = new NIOFSDirectory(proj.indexDir());
             var reader = DirectoryReader.open(dir)) {
            var searcher = new IndexSearcher(reader);
            var parser = new MultiFieldQueryParser(
                new String[]{"title_idx", "doc_id_idx", "body"}, new JapaneseAnalyzer(),
                Map.of("title_idx", 3.0f, "doc_id_idx", 5.0f, "body", 1.0f));
            var hits = searcher.search(parser.parse(queryStr), 1000);
            var stored = searcher.storedFields();
            for (var hit : hits.scoreDocs) {
                var doc = stored.document(hit.doc);
                out.add(Map.of(
                    "project",  proj.name(),
                    "title",    doc.get("title") != null ? doc.get("title") : "",
                    "pagePath", doc.get("path")  != null ? doc.get("path")  : "",
                    "summary",  doc.get("summary") != null ? doc.get("summary") : ""
                ));
            }
        } catch (Exception e) {
            System.err.println("Search error [" + proj.name() + "]: " + e.getMessage());
        }
    }

    // ---- Per-project search endpoint ----------------------------

    private void handleSearch(HttpExchange ex, Project proj) throws IOException {
        String q = "";
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                if (kv.startsWith("q=")) {
                    q = URLDecoder.decode(kv.substring(2), StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        byte[] body = searchWithProject(q, proj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    private String search(String queryStr, Path indexDir) {
        return searchWithProject(queryStr, new Project("", null, null, indexDir));
    }

    private String searchWithProject(String queryStr, Project proj) {
        if (queryStr.isBlank()) return "[]";
        try (var dir = new NIOFSDirectory(proj.indexDir());
             var reader = DirectoryReader.open(dir)) {
            var searcher = new IndexSearcher(reader);
            var parser = new MultiFieldQueryParser(
                new String[]{"title_idx", "body"},
                new JapaneseAnalyzer(),
                Map.of("title_idx", 3.0f, "body", 1.0f));
            var q = parser.parse(queryStr);
            var hits = searcher.search(q, 20);
            var stored = searcher.storedFields();
            var sb = new StringBuilder("[");
            boolean first = true;
            for (var hit : hits.scoreDocs) {
                var doc = stored.document(hit.doc);
                if (!first) sb.append(",");
                first = false;
                sb.append("{")
                  .append("\"title\":").append(jsonStr(doc.get("title"))).append(",")
                  .append("\"path\":").append(jsonStr((proj.name().isEmpty() ? "" : "/" + proj.name()) + doc.get("path"))).append(",")
                  .append("\"pagePath\":").append(jsonStr(doc.get("path"))).append(",")
                  .append("\"project\":").append(jsonStr(proj.name())).append(",")
                  .append("\"summary\":").append(jsonStr(doc.get("summary")))
                  .append("}");
            }
            return sb.append("]").toString();
        } catch (Exception e) {
            System.err.println("Search error: " + e.getMessage());
            return "[]";
        }
    }

    // ---- Static file endpoint -----------------------------------

    private void handleStatic(HttpExchange ex, Project proj, String rest) throws IOException {
        Path file = proj.staticDir().resolve(rest.replaceFirst("^/", "")).normalize();
        if (!file.startsWith(proj.staticDir())) { respond(ex, 403, "text/plain", "Forbidden"); return; }
        if (!Files.exists(file) || Files.isDirectory(file)) {
            respond(ex, 404, "text/html",
                "<html><body><h1>404 Not Found</h1><p>" + escHtml(rest) + "</p></body></html>");
            return;
        }
        byte[] body = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.toString()));
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    // ---- Helpers ------------------------------------------------

    private String pathToBreadcrumb(String path) {
        if (path == null || path.isEmpty()) return "";
        return Arrays.stream(path.replaceFirst("^/", "").split("/"))
            .map(seg -> seg.replaceFirst("^\\d+_", "").replaceAll("\\.html$", ""))
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.joining(" › "));
    }

    private String queryParam(HttpExchange ex, String key) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return "";
        for (String kv : raw.split("&")) {
            if (kv.startsWith(key + "=")) {
                return URLDecoder.decode(kv.substring(key.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private void respond(HttpExchange ex, int code, String ct, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(code, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }

    private String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css"))  return "text/css";
        if (path.endsWith(".js"))   return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        return "application/octet-stream";
    }

    private String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String jsonStr(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
