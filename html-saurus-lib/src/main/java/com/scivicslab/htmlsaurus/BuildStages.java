package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The three stages that turn a project's Markdown into something a reader can use: the static
 * HTML, the Lucene full-text index, and the embedding vectors.
 *
 * <p>They run in that order because each reads what the one before it wrote — the index reflects
 * the built pages, and the embedding cache is keyed off the index. build-only mode runs whichever
 * of them it was asked for and stops; production mode and portal mode run them and then serve
 * what came out. That is why the stages live in the library and not in a mode.
 */
public final class BuildStages {

    private BuildStages() {}

    /**
     * Set by {@code --no-diagrams}. The figures next to the Markdown are normally rebuilt as part
     * of the HTML stage; this turns that off for a run that only needs the pages.
     */
    private static boolean skipDiagrams = false;

    /** Stops the HTML stage from regenerating the figures beside the Markdown. */
    public static void skipDiagrams(boolean skip) {
        skipDiagrams = skip;
    }

    /** Number of semantic neighbours kept per document. */
    public static final int SEMANTIC_TOP_K = 20;

    /**
     * Ensures each project's embedding vector cache ({@code search-embedding/vectors.bin})
     * is present and not older than its {@code search-index/}, (re)building stale ones via
     * the shared embedding server. The model is NOT run in-process: each document's text is
     * sent over HTTP, to {@code quarkus-gpu-broker} when {@code GPU_BROKER_URL} is set and to
     * {@link EmbeddingClient#DEFAULT_BASE_URL} otherwise ({@link EmbeddingClient#defaultBaseUrl}).
     * {@code EMBEDDING_SERVER_URL} overrides both.
     *
     * <p>If the embedding server is unreachable, this logs a warning and returns: stale or
     * missing projects keep whatever cached vectors they already have (possibly none), so the
     * semantic widget shows fewer/no results, while the independent TF-IDF related-docs and
     * the portal itself are unaffected.
     *
     * @param projects Docusaurus project directories whose built indexes are embedded
     */
    public static void ensureSemanticVectors(List<Path> projects) {
        String url = System.getenv("EMBEDDING_SERVER_URL");
        EmbeddingClient embed = new EmbeddingClient(url);
        if (!embed.isReachable()) {
            System.err.println("WARNING: embedding server not reachable at " + embed.baseUrl()
                    + " — semantic vectors not (re)built this run; using any cached vectors. "
                    + "(TF-IDF related-docs unaffected.) Set EMBEDDING_SERVER_URL to override.");
            return;
        }
        System.out.println("Ensuring semantic vectors via " + embed.baseUrl() + " ...");
        SemanticIndexer.ensureVectors(projects, embed);
    }

    /** Builds with {@link SiteBuilder}'s own default page-conversion parallelism. */
    public static void build(Path docsDir, Path outDir, boolean production) {
        build(docsDir, outDir, production, 0);
    }

    /**
     * Builds static HTML from Markdown files in the docs directory.
     *
     * @param docsDir    source directory containing Markdown files
     * @param outDir     target directory for generated HTML files
     * @param production whether to build in production mode
     * @param threads    page-conversion parallelism (see {@link SiteBuilder#threads}); {@code <= 0}
     *                   keeps {@link SiteBuilder}'s own default
     */
    public static void build(Path docsDir, Path outDir, boolean production, int threads) {
        try {
            // Figures first: the .png a page embeds is regenerated from its .jsh when out of date,
            // so a document and its diagrams are never out of step (DiagramBuilder).
            if (!skipDiagrams) DiagramBuilder.rebuild(docsDir);
            // The .md a page is built from is regenerated from its .lisp when out of date, for the
            // same reason (RuleFileBuilder).
            if (!skipDiagrams) RuleFileBuilder.rebuild(docsDir);
            Path projectDir = docsDir.getParent();
            String[] i18n = Projects.readI18nConfig(projectDir);
            String defaultLocale = i18n.length > 0 ? i18n[0] : null;

            // Collect locales that have actual docs (default + alternates with i18n content)
            List<String> availableLocales = new ArrayList<>();
            if (defaultLocale != null) availableLocales.add(defaultLocale);
            for (int i = 1; i < i18n.length; i++) {
                Path localeDocs = projectDir.resolve(
                    "i18n/" + i18n[i] + "/docusaurus-plugin-content-docs/current");
                if (Projects.hasMarkdownFiles(localeDocs)) availableLocales.add(i18n[i]);
            }
            List<String> localeList = List.copyOf(availableLocales);

            // Build default locale
            SiteBuilder defaultBuilder = new SiteBuilder(docsDir, outDir, production, defaultLocale, defaultLocale, localeList);
            if (threads > 0) defaultBuilder.threads(threads);
            defaultBuilder.build();
            System.out.println("  build done : " + docsDir);

            // Build each alternate locale
            for (int i = 1; i < localeList.size(); i++) {
                String locale = localeList.get(i);
                Path localeDocs = projectDir.resolve(
                    "i18n/" + locale + "/docusaurus-plugin-content-docs/current");
                Path localeOut = outDir.resolve(locale);
                SiteBuilder localeBuilder = new SiteBuilder(localeDocs, localeOut, production, locale, defaultLocale, localeList);
                if (threads > 0) localeBuilder.threads(threads);
                localeBuilder.build();
                System.out.println("  build done : " + localeDocs);
            }
        } catch (IOException e) {
            System.err.println("Build failed: " + e.getMessage());
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
    public static void reindexAll(Path projectDir, boolean production) {
        String[] i18n = Projects.readI18nConfig(projectDir);
        String defaultLocale = i18n.length > 0 ? i18n[0] : null;
        Path baseIndexDir = projectDir.resolve("search-index");

        // Index default locale, with the blog posts that sit beside its docs
        reindex(projectDir.resolve("docs"), baseIndexDir, defaultLocale, defaultLocale, production,
                projectDir.resolve("blog"));

        // Index alternate locales
        for (int i = 1; i < i18n.length; i++) {
            String locale = i18n[i];
            Path localeDocs = projectDir.resolve(
                "i18n/" + locale + "/docusaurus-plugin-content-docs/current");
            if (Projects.hasMarkdownFiles(localeDocs)) {
                reindex(localeDocs, baseIndexDir.resolve(locale), locale, defaultLocale, production,
                        projectDir.resolve("i18n/" + locale + "/docusaurus-plugin-content-blog"));
            }
        }
    }

    /**
     * Builds a Lucene full-text search index from Markdown files.
     *
     * @param docsDir  source directory containing Markdown files
     * @param indexDir target directory for the Lucene index
     */
    public static void reindex(Path docsDir, Path indexDir) {
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
    public static void reindex(Path docsDir, Path indexDir, String locale, boolean production) {
        reindex(docsDir, indexDir, locale, production, null);
    }

    /**
     * Builds the index for one locale from its docs and its blog posts.
     *
     * @param blogDir the locale's blog directory, or {@code null} when the project has no blog
     */
    public static void reindex(Path docsDir, Path indexDir, String locale, boolean production, Path blogDir) {
        reindex(docsDir, indexDir, locale, "ja", production, blogDir);
    }

    /**
     * Builds the index for one locale, told which locale the site writes at the root.
     *
     * @param defaultLocale the project's default locale, whose pages carry no locale segment
     */
    public static void reindex(Path docsDir, Path indexDir, String locale, String defaultLocale,
                        boolean production, Path blogDir) {
        try {
            Files.createDirectories(indexDir);
            new SearchIndexer(docsDir, indexDir, locale, defaultLocale, production, blogDir).index();
            System.out.println("  index done : " + indexDir);
        } catch (IOException e) {
            System.err.println("Index failed: " + e.getMessage());
        }
    }
}
