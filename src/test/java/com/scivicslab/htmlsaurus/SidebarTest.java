package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies sidebar rendering for the dir/dir.md convention as specified in
 * DocPageSidebar_260401_oo01.
 *
 * <p>Key rules:
 * <ul>
 *   <li><b>Pattern 1</b>: dir contains only a same-name .md (no subdirs) → leaf link, no category.</li>
 *   <li><b>Pattern 2</b>: dir contains a same-name .md AND subdirs → category header is a
 *       clickable link pointing to the same-name page; the same-name .md does NOT appear as a
 *       child item in the sidebar.</li>
 * </ul>
 */
class SidebarTest {

    @TempDir
    Path tempDir;

    private Path createProject(String name) throws IOException {
        Path projectDir = tempDir.resolve(name);
        Files.createDirectories(projectDir.resolve("docs"));
        Files.writeString(projectDir.resolve("docusaurus.config.js"), "module.exports = {};");
        return projectDir;
    }

    private void writeDoc(Path docsDir, String relPath, String title) throws IOException {
        Path file = docsDir.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ntitle: " + title + "\n---\n\n# " + title + "\n\nContent.");
    }

    private String readHtml(Path outDir, String relPath) throws IOException {
        return Files.readString(outDir.resolve(relPath));
    }

    // ---- index.html generation -------------------------------------------

    /**
     * Regression tests for index.html generation.
     *
     * <p>Prior to the fix, buildTree() set SiteNode.href = catHref (null when no same-name .md),
     * causing SiteBuilder.build() to skip generating index.html for sites whose top-level
     * categories had no same-name .md file. The portal server then returned 404 for the root URL.
     */
    @Nested
    @DisplayName("index.html: always generated regardless of category type")
    class IndexHtml {

        @Test
        @DisplayName("index.html generated when top-level category has no same-name .md")
        void indexHtml_generatedForPlainCategory() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            // Plain category: dir with .md files but no same-name dir/dir.md
            writeDoc(docsDir, "010_section/intro.md", "Intro");
            writeDoc(docsDir, "010_section/guide.md", "Guide");

            Main.build(docsDir, proj.resolve("static-html"), false);

            assertTrue(Files.exists(proj.resolve("static-html/index.html")),
                    "index.html must be generated even when top-level category has no same-name .md");
        }

        @Test
        @DisplayName("index.html generated when top-level category is Pattern 2 (same-name .md + subdirs)")
        void indexHtml_generatedForPattern2Category() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            writeDoc(docsDir, "010_section/010_section.md", "Section");
            writeDoc(docsDir, "010_section/020_sub/020_sub.md", "Sub");

            Main.build(docsDir, proj.resolve("static-html"), false);

            assertTrue(Files.exists(proj.resolve("static-html/index.html")),
                    "index.html must be generated even when top-level category is Pattern 2");
        }

        @Test
        @DisplayName("index.html redirects to first page (not '#') in dev mode")
        void indexHtml_redirectsToFirstPage() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            writeDoc(docsDir, "010_section/intro.md", "Intro");

            Main.build(docsDir, proj.resolve("static-html"), false);

            String indexHtml = Files.readString(proj.resolve("static-html/index.html"));
            assertTrue(indexHtml.contains("section/intro.html"),
                    "index.html must redirect to the first page, not '#'");
            assertFalse(indexHtml.contains("url=#") || indexHtml.contains("url=\"#\""),
                    "index.html must not redirect to '#'");
        }
    }

    // ---- Pattern 1 -------------------------------------------------------

    @Nested
    @DisplayName("Pattern 1: dir/dir.md only (leaf page, no category)")
    class Pattern1 {

        @Test
        @DisplayName("dir/dir.md appears as a plain sidebar link, not a category")
        void dirDirMd_onlyFile_isLeafLink() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            writeDoc(docsDir, "010_section/010_section.md", "Section");

            Main.build(docsDir, proj.resolve("static-html"), false);

            // Any generated page's sidebar should contain an <a> link to section.html,
            // not a cat-header with no link.
            String html = readHtml(proj.resolve("static-html"), "section.html");
            assertTrue(html.contains("href=\"section.html\"") || html.contains("href=\"./section.html\""),
                    "Pattern 1: same-name .md must produce a plain <a> link in the sidebar");
            assertFalse(html.contains("cat-header") && !html.contains("cat-label"),
                    "Pattern 1: must not produce a non-linked category header");
        }
    }

    // ---- Pattern 2 -------------------------------------------------------

    @Nested
    @DisplayName("Pattern 2: dir/dir.md + subdirs (clickable category header)")
    class Pattern2 {

        /**
         * 3-level structure to observe the Pattern 2 category header in the sidebar.
         *
         * docs/
         *   010_section/                      ← top-level section (navbar)
         *     020_guide/                      ← sub-category (Pattern 2)
         *       020_guide.md                  ← same-name .md → section/guide.html
         *       030_detail/
         *         030_detail.md               ← detail page → section/guide/detail.html
         *
         * When viewing section/guide/detail.html, the sidebar for "section" shows:
         *   [Guide ▶] ← cat-header with <a class="cat-label"> link to section/guide.html
         *     Detail  ← leaf link
         */
        private void buildPattern2Project(Path docsDir) throws IOException {
            writeDoc(docsDir, "010_section/010_section.md", "Section");
            writeDoc(docsDir, "010_section/020_guide/020_guide.md", "Guide");
            writeDoc(docsDir, "010_section/020_guide/030_detail/030_detail.md", "Detail");
        }

        @Test
        @DisplayName("Category header renders as <a> link with class=\"cat-label\" in dev mode")
        void categoryHeader_isLink_devMode() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            buildPattern2Project(docsDir);

            Main.build(docsDir, proj.resolve("static-html"), false);

            // detail page is inside section/guide → sidebar shows Guide as a sub-category
            String html = readHtml(proj.resolve("static-html"), "section/guide/detail.html");
            // Check that a clickable link with class="cat-label" is rendered in the cat-header
            // (href comes before class in the generated markup: <a href="..." class="cat-label">)
            assertTrue(html.contains("class=\"cat-label\"") && html.contains("cat-header"),
                    "Pattern 2: sub-category header must render as a link with class=\"cat-label\"");
            assertFalse(html.contains("<span class=\"cat-label\">"),
                    "Pattern 2: cat-label must NOT be a <span> when category has a link");
        }

        @Test
        @DisplayName("Category header links to section/guide.html (directory URL) in dev mode")
        void categoryHeader_linksToGuide_devMode() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            buildPattern2Project(docsDir);

            Main.build(docsDir, proj.resolve("static-html"), false);

            String html = readHtml(proj.resolve("static-html"), "section/guide/detail.html");
            assertTrue(html.contains("section/guide.html") || html.contains("guide.html"),
                    "Pattern 2: category header must link to guide.html (dir URL)");
        }

        @Test
        @DisplayName("Same-name .md does NOT appear as a child item in the sidebar")
        void sameNameMd_notChildItem_devMode() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            buildPattern2Project(docsDir);

            Main.build(docsDir, proj.resolve("static-html"), false);

            String html = readHtml(proj.resolve("static-html"), "section/guide/detail.html");
            // The sidebar must NOT contain a plain link to guide/guide.html (deep path)
            assertFalse(html.contains("guide/guide.html"),
                    "Pattern 2: same-name .md must not appear as a child link (guide/guide.html)");
        }

        @Test
        @DisplayName("Category header renders as link with class=\"cat-label\" in production mode")
        void categoryHeader_linksToGuide_productionMode() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            buildPattern2Project(docsDir);

            Main.build(docsDir, proj.resolve("static-html"), true);

            String html = readHtml(proj.resolve("static-html"), "section/guide/detail/index.html");
            assertTrue(html.contains("class=\"cat-label\"") && html.contains("cat-header"),
                    "Pattern 2: category header must render as a link with class=\"cat-label\" in production mode");
            assertFalse(html.contains("<span class=\"cat-label\">"),
                    "Pattern 2: cat-label must NOT be a <span> when category has a link");
        }

        @Test
        @DisplayName("Same-name .md collapses to parent dir URL (not deep path) in dev mode")
        void sameNameMd_url_collapsesToDir() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            buildPattern2Project(docsDir);

            Main.build(docsDir, proj.resolve("static-html"), false);

            // section/guide.html must exist (collapsed dir URL)
            assertTrue(Files.exists(proj.resolve("static-html/section/guide.html")),
                    "Pattern 2: same-name .md must produce section/guide.html (not section/guide/guide.html)");
            // section/guide/guide.html must NOT exist
            assertFalse(Files.exists(proj.resolve("static-html/section/guide/guide.html")),
                    "Pattern 2: deep path section/guide/guide.html must not exist");
        }

        @Test
        @DisplayName("Same-name .md collapses to parent dir URL in production mode")
        void sameNameMd_url_collapsesToDir_productionMode() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            buildPattern2Project(docsDir);

            Main.build(docsDir, proj.resolve("static-html"), true);

            assertTrue(Files.exists(proj.resolve("static-html/section/guide/index.html")),
                    "Pattern 2: same-name .md must produce section/guide/index.html in production mode");
            assertFalse(Files.exists(proj.resolve("static-html/section/guide/guide/index.html")),
                    "Pattern 2: deep path section/guide/guide/index.html must not exist");
        }
    }
}
