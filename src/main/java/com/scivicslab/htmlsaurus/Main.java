package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Entry point for html-saurus. Supports two modes of operation:
 *
 * <ul>
 *   <li><b>Single-project mode</b> (default): converts one Docusaurus project's
 *       {@code docs/} directory into static HTML, optionally serving it.</li>
 *   <li><b>Portal mode</b> ({@code --portal-mode}): discovers all Docusaurus projects
 *       under a root directory and serves them through a unified portal with cross-project search.</li>
 * </ul>
 *
 * <p>Usage: {@code java -jar html-saurus.jar [path] [--serve] [--portal-mode] [--port N] [--production]}
 */
public class Main {

    /**
     * Parses command-line arguments and launches either single-project or portal mode.
     *
     * @param args command-line arguments
     * @throws Exception if an I/O error or build failure occurs
     */
    public static void main(String[] args) throws Exception {
        Path rootDir = null;
        boolean serve = false;
        boolean portalMode = false;
        boolean production = false;
        int port = 8080;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--serve")) serve = true;
            else if (args[i].equals("--portal-mode")) portalMode = true;
            else if (args[i].equals("--production")) production = true;
            else if (!args[i].startsWith("--")) rootDir = Path.of(args[i]).toAbsolutePath();
        }

        // --production implies --serve
        if (production) serve = true;

        if (rootDir == null) rootDir = Path.of("").toAbsolutePath();

        if (portalMode) {
            runPortal(rootDir, port, serve, production);
        } else {
            runSingle(rootDir, port, serve, production);
        }
    }

    /**
     * Runs in single-project mode: builds, indexes, and optionally serves one Docusaurus project.
     *
     * @param projectDir root directory of the Docusaurus project (must contain {@code docs/})
     * @param port       HTTP port for the development server
     * @param serve      whether to start a development server
     * @param production whether to run in production mode
     * @throws Exception if an I/O error occurs
     */
    private static void runSingle(Path projectDir, int port, boolean serve,
                                   boolean production) throws Exception {
        Path docsDir  = projectDir.resolve("docs");
        Path outDir   = projectDir.resolve("static-html");
        Path indexDir = projectDir.resolve("search-index");

        System.out.println("project : " + projectDir);
        build(docsDir, outDir, production);

        if (serve) {
            // Build search index only when serving; build-only mode does not need it.
            reindexAll(projectDir, production);
            Runnable rebuild = () -> { build(docsDir, outDir, false); reindexAll(projectDir, false); };
            new SearchServer(outDir, indexDir, port, rebuild, production, docsDir).start();
        }
    }

    /**
     * Runs in portal mode: discovers all Docusaurus projects under {@code worksDir},
     * builds them, and optionally serves them through a unified portal.
     *
     * @param worksDir   root directory containing multiple Docusaurus projects
     * @param port       HTTP port for the portal server
     * @param serve      whether to start the portal server
     * @param production whether to run in production mode
     * @throws Exception if an I/O error occurs
     */
    private static void runPortal(Path worksDir, int port, boolean serve,
                                   boolean production) throws Exception {
        List<Path> projects = findProjects(worksDir);
        if (projects.isEmpty()) {
            System.err.println("No Docusaurus projects found under " + worksDir);
            return;
        }

        System.out.println("Portal root : " + worksDir);
        System.out.println("Projects    : " + projects.size());

        for (Path p : projects) {
            System.out.println("  [" + p.getFileName() + "]");
            Path staticDir = p.resolve("static-html");
            Path indexDir  = p.resolve("search-index");
            if (!Files.isDirectory(staticDir)) {
                build(p.resolve("docs"), staticDir, production);
            }
            if (!Files.isDirectory(indexDir)) {
                reindexAll(p, production);
            }
        }

        if (serve) {
            new PortalServer(worksDir, projects, port, production).start();
        }
    }

    /**
     * Discovers Docusaurus projects under the given directory.
     * A directory is considered a Docusaurus project if it contains both a {@code docs/}
     * subdirectory and a {@code docusaurus.config.js} or {@code docusaurus.config.ts} file.
     *
     * @param worksDir the parent directory to scan
     * @return sorted list of paths to detected Docusaurus project directories
     * @throws IOException if the directory cannot be listed
     */
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

    /**
     * Builds static HTML from Markdown files in the docs directory.
     *
     * @param docsDir    source directory containing Markdown files
     * @param outDir     target directory for generated HTML files
     * @param production whether to build in production mode
     */
    static void build(Path docsDir, Path outDir, boolean production) {
        try {
            Path projectDir = docsDir.getParent();
            String[] i18n = readI18nConfig(projectDir);
            String defaultLocale = i18n.length > 0 ? i18n[0] : null;

            // Collect locales that have actual docs (default + alternates with i18n content)
            List<String> availableLocales = new ArrayList<>();
            if (defaultLocale != null) availableLocales.add(defaultLocale);
            for (int i = 1; i < i18n.length; i++) {
                Path localeDocs = projectDir.resolve(
                    "i18n/" + i18n[i] + "/docusaurus-plugin-content-docs/current");
                if (hasMarkdownFiles(localeDocs)) availableLocales.add(i18n[i]);
            }
            List<String> localeList = List.copyOf(availableLocales);

            // Build default locale
            new SiteBuilder(docsDir, outDir, production, defaultLocale, defaultLocale, localeList).build();
            System.out.println("  build done : " + docsDir);

            // Build each alternate locale
            for (int i = 1; i < localeList.size(); i++) {
                String locale = localeList.get(i);
                Path localeDocs = projectDir.resolve(
                    "i18n/" + locale + "/docusaurus-plugin-content-docs/current");
                Path localeOut = outDir.resolve(locale);
                new SiteBuilder(localeDocs, localeOut, production, locale, defaultLocale, localeList).build();
                System.out.println("  build done : " + localeDocs);
            }
        } catch (IOException e) {
            System.err.println("Build failed: " + e.getMessage());
        }
    }

    /**
     * Reads i18n configuration from {@code docusaurus.config.ts} or {@code .js}.
     * Returns an array where index 0 is the defaultLocale and subsequent entries are
     * alternate locales. Returns an empty array if no i18n config is found.
     */
    static String[] readI18nConfig(Path projectDir) {
        Path config = null;
        for (String name : new String[]{"docusaurus.config.ts", "docusaurus.config.js"}) {
            Path p = projectDir.resolve(name);
            if (Files.exists(p)) { config = p; break; }
        }
        if (config == null) return new String[0];
        try {
            String content = Files.readString(config);
            var defM = java.util.regex.Pattern.compile("defaultLocale:\\s*['\"]([^'\"]+)['\"]")
                           .matcher(content);
            if (!defM.find()) return new String[0];
            String defaultLocale = defM.group(1);

            var locM = java.util.regex.Pattern.compile("locales:\\s*\\[([^\\]]+)\\]")
                           .matcher(content);
            List<String> locales = new ArrayList<>();
            locales.add(defaultLocale);
            if (locM.find()) {
                var itemM = java.util.regex.Pattern.compile("['\"]([^'\"]+)['\"]")
                                .matcher(locM.group(1));
                while (itemM.find()) {
                    String loc = itemM.group(1);
                    if (!loc.equals(defaultLocale)) locales.add(loc);
                }
            }
            return locales.toArray(new String[0]);
        } catch (IOException e) {
            return new String[0];
        }
    }

    /** Returns true if {@code dir} exists and contains at least one {@code .md} file. */
    static boolean hasMarkdownFiles(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".md"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Rebuilds search indexes for all locales of a project.
     * Indexes the default locale into {@code search-index/} and each alternate locale
     * (when it has Markdown content) into {@code search-index/<locale>/}.
     *
     * @param projectDir root directory of the project
     * @param production whether to use production-mode clean URLs in the index
     */
    static void reindexAll(Path projectDir, boolean production) {
        String[] i18n = readI18nConfig(projectDir);
        String defaultLocale = i18n.length > 0 ? i18n[0] : null;
        Path baseIndexDir = projectDir.resolve("search-index");

        // Index default locale
        reindex(projectDir.resolve("docs"), baseIndexDir, defaultLocale, production);

        // Index alternate locales
        for (int i = 1; i < i18n.length; i++) {
            String locale = i18n[i];
            Path localeDocs = projectDir.resolve(
                "i18n/" + locale + "/docusaurus-plugin-content-docs/current");
            if (hasMarkdownFiles(localeDocs)) {
                reindex(localeDocs, baseIndexDir.resolve(locale), locale, production);
            }
        }
    }

    /**
     * Builds a Lucene full-text search index from Markdown files.
     *
     * @param docsDir  source directory containing Markdown files
     * @param indexDir target directory for the Lucene index
     */
    static void reindex(Path docsDir, Path indexDir) {
        reindex(docsDir, indexDir, null, false);
    }

    /**
     * Builds a Lucene full-text search index from Markdown files for a specific locale.
     *
     * @param docsDir    source directory containing Markdown files
     * @param indexDir   target directory for the Lucene index
     * @param locale     locale code (e.g. {@code "ja"}, {@code "en"}), or {@code null} for Japanese default
     * @param production whether to use production-mode clean URLs
     */
    static void reindex(Path docsDir, Path indexDir, String locale, boolean production) {
        try {
            Files.createDirectories(indexDir);
            new SearchIndexer(docsDir, indexDir, locale, production).index();
            System.out.println("  index done : " + indexDir);
        } catch (IOException e) {
            System.err.println("Index failed: " + e.getMessage());
        }
    }
}
