package com.scivicslab.yadoc;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class Main {

    public static void main(String[] args) throws Exception {
        Path rootDir = null;
        boolean watch = false;
        boolean serve = false;
        boolean portalMode = false;
        int port = 8080;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--watch")) watch = true;
            else if (args[i].equals("--serve")) serve = true;
            else if (args[i].equals("--portal-mode")) portalMode = true;
            else if (!args[i].startsWith("--")) rootDir = Path.of(args[i]).toAbsolutePath();
        }

        if (rootDir == null) rootDir = Path.of("").toAbsolutePath();

        if (portalMode) {
            runPortal(rootDir, port, serve, watch);
        } else {
            runSingle(rootDir, port, serve, watch);
        }
    }

    // ---- Single project mode ------------------------------------

    private static void runSingle(Path projectDir, int port, boolean serve, boolean watch)
            throws Exception {
        Path docsDir  = projectDir.resolve("docs");
        Path outDir   = projectDir.resolve("static-html");
        Path indexDir = projectDir.resolve("search-index");

        System.out.println("project : " + projectDir);
        build(docsDir, outDir);
        reindex(docsDir, indexDir);

        if (serve) {
            if (watch) startWatchThread(docsDir, outDir, indexDir);
            Runnable rebuild = () -> { build(docsDir, outDir); reindex(docsDir, indexDir); };
            new SearchServer(outDir, indexDir, port, rebuild).start();
        } else if (watch) {
            watchAndRebuild(docsDir, outDir, indexDir);
        }
    }

    // ---- Portal mode --------------------------------------------

    private static void runPortal(Path worksDir, int port, boolean serve, boolean watch)
            throws Exception {
        List<Path> projects = findProjects(worksDir);
        if (projects.isEmpty()) {
            System.err.println("No Docusaurus projects found under " + worksDir);
            return;
        }

        System.out.println("Portal root : " + worksDir);
        System.out.println("Projects    : " + projects.size());

        for (Path p : projects) {
            System.out.println("  [" + p.getFileName() + "]");
            build(p.resolve("docs"), p.resolve("static-html"));
            reindex(p.resolve("docs"), p.resolve("search-index"));
        }

        if (serve) {
            if (watch) startPortalWatchThread(projects);
            new PortalServer(projects, port).start();
        } else if (watch) {
            portalWatchAndRebuild(projects);
        }
    }

    // Detect Docusaurus projects: direct subdirs of worksDir that contain docs/
    static List<Path> findProjects(Path worksDir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(worksDir)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> Files.isDirectory(p.resolve("docs"))
                            && (Files.exists(p.resolve("docusaurus.config.js"))
                             || Files.exists(p.resolve("docusaurus.config.ts"))))
                  .sorted()
                  .forEach(result::add);
        }
        return result;
    }

    // ---- Build & index ------------------------------------------

    static void build(Path docsDir, Path outDir) {
        try {
            new SiteBuilder(docsDir, outDir).build();
            System.out.println("  build done : " + docsDir);
        } catch (IOException e) {
            System.err.println("Build failed: " + e.getMessage());
        }
    }

    static void reindex(Path docsDir, Path indexDir) {
        try {
            Files.createDirectories(indexDir);
            new SearchIndexer(docsDir, indexDir).index();
            System.out.println("  index done : " + indexDir);
        } catch (IOException e) {
            System.err.println("Index failed: " + e.getMessage());
        }
    }

    // ---- Watch helpers ------------------------------------------

    private static void watchAndRebuild(Path docsDir, Path outDir, Path indexDir)
            throws IOException, InterruptedException {
        System.out.println("Watching for changes... (Ctrl+C to stop)");
        WatchService watcher = FileSystems.getDefault().newWatchService();
        Map<WatchKey, Path> keys = new HashMap<>();
        registerAll(watcher, keys, docsDir);
        watchLoop(watcher, keys, docsDir, outDir, indexDir);
    }

    private static void startWatchThread(Path docsDir, Path outDir, Path indexDir) {
        Thread t = new Thread(() -> {
            try { watchAndRebuild(docsDir, outDir, indexDir); }
            catch (InterruptedException ignored) {}
            catch (IOException e) { System.err.println("Watch error: " + e.getMessage()); }
        });
        t.setDaemon(true);
        t.start();
        System.out.println("Watch thread started.");
    }

    // Portal watch: single WatchService across all projects, map key → project
    private static void portalWatchAndRebuild(List<Path> projects)
            throws IOException, InterruptedException {
        System.out.println("Watching all projects... (Ctrl+C to stop)");
        WatchService watcher = FileSystems.getDefault().newWatchService();
        Map<WatchKey, Path[]> keyToProject = new HashMap<>(); // [docsDir, outDir, indexDir]

        for (Path p : projects) {
            Path docsDir  = p.resolve("docs");
            Path outDir   = p.resolve("static-html");
            Path indexDir = p.resolve("search-index");
            registerAllPortal(watcher, keyToProject, docsDir, outDir, indexDir);
        }

        while (true) {
            WatchKey key = watcher.take();
            Path[] dirs = keyToProject.get(key);
            if (dirs == null) { key.reset(); continue; }

            boolean changed = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                @SuppressWarnings("unchecked")
                Path child = dirs[0].resolve(((WatchEvent<Path>) event).context());
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child))
                    registerAllPortal(watcher, keyToProject, child, dirs[1], dirs[2]);
                changed = true;
            }
            if (changed) {
                Thread.sleep(200);
                System.out.println("\nChange detected in " + dirs[0].getParent().getFileName());
                build(dirs[0], dirs[1]);
                reindex(dirs[0], dirs[2]);
            }
            if (!key.reset()) { keyToProject.remove(key); if (keyToProject.isEmpty()) break; }
        }
    }

    private static void startPortalWatchThread(List<Path> projects) {
        Thread t = new Thread(() -> {
            try { portalWatchAndRebuild(projects); }
            catch (InterruptedException ignored) {}
            catch (IOException e) { System.err.println("Watch error: " + e.getMessage()); }
        });
        t.setDaemon(true);
        t.start();
        System.out.println("Watch thread started.");
    }

    // ---- WatchService registration helpers ----------------------

    private static void watchLoop(WatchService watcher, Map<WatchKey, Path> keys,
                                   Path docsDir, Path outDir, Path indexDir)
            throws IOException, InterruptedException {
        while (true) {
            WatchKey key = watcher.take();
            Path dir = keys.get(key);
            boolean changed = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                @SuppressWarnings("unchecked")
                Path child = dir.resolve(((WatchEvent<Path>) event).context());
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child))
                    registerAll(watcher, keys, child);
                changed = true;
            }
            if (changed) {
                Thread.sleep(200);
                System.out.println("\nChange detected, rebuilding...");
                build(docsDir, outDir);
                reindex(docsDir, indexDir);
            }
            if (!key.reset()) { keys.remove(key); if (keys.isEmpty()) break; }
        }
    }

    private static void registerAll(WatchService watcher, Map<WatchKey, Path> keys, Path start)
            throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                keys.put(dir.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE), dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void registerAllPortal(WatchService watcher, Map<WatchKey, Path[]> keys,
                                           Path docsDir, Path outDir, Path indexDir)
            throws IOException {
        Files.walkFileTree(docsDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                keys.put(dir.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE),
                    new Path[]{docsDir, outDir, indexDir});
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
