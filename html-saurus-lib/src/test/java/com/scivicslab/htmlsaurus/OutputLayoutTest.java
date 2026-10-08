package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One output layout, whatever the mode: a page is written to {@code <page>/index.html} and the files
 * placed beside its Markdown source are copied into that same directory.
 *
 * <p>The two modes used to differ. A page was written to {@code ISMS_Certificate/index.html} in
 * production and to {@code ISMS_Certificate.html} in every other mode, one level above the directory
 * the sibling files were copied to. The author writes one string, {@code ISMS_2022.pdf}, and the
 * browser resolves it against the directory holding the page, so the same string named an existing
 * file in production and a missing one elsewhere. An image survived only because the builder
 * prefixed the directory name onto {@code src} in that other mode; no such prefix was put on
 * {@code href}, so a PDF link answered 404 on the dev portal and 200 on the published site.
 */
@Tag("S1")
class OutputLayoutTest {

    @TempDir
    Path tempDir;

    private Path createProject() throws IOException {
        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir.resolve("docs"));
        Files.writeString(projectDir.resolve("docusaurus.config.js"), "module.exports = {};");
        return projectDir;
    }

    /** The dir/dir.md convention: the page is the directory's own index. */
    private void writeSameNameDoc(Path docsDir) throws IOException {
        Path dir = docsDir.resolve("guides/020_security_policy/010_ISMS_Certificate");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("010_ISMS_Certificate.md"),
                "---\nid: ISMS_Certificate\ntitle: ISMS\n---\n\n"
                        + "<a href=\"ISMS_2022.pdf\">certificate</a>\n\n![logo](logo.png)\n");
        Files.writeString(dir.resolve("ISMS_2022.pdf"), "%PDF-1.4\n");
        Files.writeString(dir.resolve("logo.png"), "png");
    }

    /** A plain file, whose page lands one level below its own source directory. */
    private void writePlainDoc(Path docsDir) throws IOException {
        Path dir = docsDir.resolve("guides");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("010_terms.md"),
                "---\ntitle: Terms\n---\n\n<a href=\"policy.pdf\">policy</a>\n");
        Files.writeString(dir.resolve("policy.pdf"), "%PDF-1.4\n");
    }

    private void build(Path proj, boolean production) {
        BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), production);
    }

    @Test
    void sameNamePageAndItsFilesShareOneDirectoryInEveryMode() throws IOException {
        for (boolean production : new boolean[] {false, true}) {
            Path proj = createProject();
            writeSameNameDoc(proj.resolve("docs"));
            build(proj, production);
            Path out = proj.resolve("static-html/guides/security_policy");

            assertTrue(Files.isRegularFile(out.resolve("ISMS_Certificate/index.html")),
                    "production=" + production + ": the page is the directory's index");
            assertFalse(Files.exists(out.resolve("ISMS_Certificate.html")),
                    "production=" + production + ": no page beside the directory");
            assertTrue(Files.isRegularFile(out.resolve("ISMS_Certificate/ISMS_2022.pdf")),
                    "production=" + production + ": the PDF sits beside the page");

            String html = Files.readString(out.resolve("ISMS_Certificate/index.html"));
            assertTrue(html.contains("href=\"ISMS_2022.pdf\""),
                    "production=" + production + ": the author's own string is kept");
            assertTrue(html.contains("src=\"logo.png\""),
                    "production=" + production + ": no directory name is prefixed");

            deleteTree(proj);
        }
    }

    @Test
    void plainPageGetsItsSiblingFilesInEveryMode() throws IOException {
        for (boolean production : new boolean[] {false, true}) {
            Path proj = createProject();
            writePlainDoc(proj.resolve("docs"));
            build(proj, production);
            Path out = proj.resolve("static-html/guides");

            assertTrue(Files.isRegularFile(out.resolve("terms/index.html")),
                    "production=" + production + ": the page is a directory index");
            assertTrue(Files.isRegularFile(out.resolve("terms/policy.pdf")),
                    "production=" + production + ": the PDF is copied beside the page");

            deleteTree(proj);
        }
    }

    /**
     * Only the files the page names are copied. The directory a page is written to is its own, so
     * copying every sibling into it duplicates files the page never asks for: in nigsc_homepage2 the
     * PDFs under guides/ would be copied once per page in that folder.
     */
    @Test
    void onlyTheFilesThePageNamesAreCopiedBesideIt() throws IOException {
        Path proj = createProject();
        Path dir = proj.resolve("docs/guides");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("010_terms.md"),
                "---\ntitle: Terms\n---\n\n<a href=\"policy.pdf\">policy</a>\n\n![fig](fig.png)\n");
        Files.writeString(dir.resolve("policy.pdf"), "%PDF-1.4\n");
        Files.writeString(dir.resolve("fig.png"), "png");
        Files.writeString(dir.resolve("unused.pdf"), "%PDF-1.4\n");
        build(proj, false);

        Path page = proj.resolve("static-html/guides/terms");
        assertTrue(Files.isRegularFile(page.resolve("policy.pdf")), "a named file is copied");
        assertTrue(Files.isRegularFile(page.resolve("fig.png")), "a named image is copied");
        assertFalse(Files.exists(page.resolve("unused.pdf")), "a file the page never names is not copied");
    }

    @Test
    void sidebarLinksPointAtDirectoriesInEveryMode() throws IOException {
        for (boolean production : new boolean[] {false, true}) {
            Path proj = createProject();
            writeSameNameDoc(proj.resolve("docs"));
            build(proj, production);

            String html = Files.readString(
                    proj.resolve("static-html/guides/security_policy/ISMS_Certificate/index.html"));
            assertFalse(html.contains("ISMS_Certificate.html"),
                    "production=" + production + ": no link names a .html page file");

            deleteTree(proj);
        }
    }

    /**
     * A blog post is published as its own directory, one level below the {@code blog/} folder its
     * source and the images sit in side by side, so the files it names have to be copied there too.
     * Nothing copied them: every image in a post that named one by a bare name answered 404, on the
     * published site as well as locally.
     */
    @Test
    void blogPostGetsTheFilesItNames() throws IOException {
        Path proj = createProject();
        Path blog = proj.resolve("blog");
        Files.createDirectories(blog);
        Files.writeString(blog.resolve("2022-07-05-news.md"),
                "---\ntitle: News\n---\n\n![screen](login_JP.png)\n");
        Files.writeString(blog.resolve("login_JP.png"), "png");
        Files.writeString(blog.resolve("unused.png"), "png");
        Files.writeString(proj.resolve("docs/intro.md"), "---\ntitle: Intro\n---\n\nContent.\n");

        build(proj, false);

        // The date prefix of the filename is not part of the slug.
        Path post = proj.resolve("static-html/blog/news");
        assertTrue(Files.isRegularFile(post.resolve("index.html")), "the post is published");
        assertTrue(Files.isRegularFile(post.resolve("login_JP.png")), "the image it names is beside it");
        assertFalse(Files.exists(post.resolve("unused.png")), "an image it never names is not copied");
    }

    /**
     * {@code dir/index.md} is the page of {@code dir}, as {@code dir/dir.md} is. Treating the file
     * name as an ordinary segment put the page at {@code dir/index/index.html}, so the directory's
     * own address answered 404 and only {@code dir/index/} worked.
     */
    @Test
    void aDirectoryIndexIsThePageOfItsDirectory() throws IOException {
        for (boolean production : new boolean[] {false, true}) {
            Path proj = createProject();
            Path dir = proj.resolve("docs/ai-tools/emacs-mcp-server");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("index.md"), "---\ntitle: Overview\n---\n\nContent.\n");
            Files.writeString(dir.resolve("tutorial.md"), "---\ntitle: Tutorial\n---\n\nMore.\n");

            build(proj, production);
            Path out = proj.resolve("static-html/ai-tools");

            assertTrue(Files.isRegularFile(out.resolve("emacs-mcp-server/index.html")),
                    "production=" + production + ": the directory's own page");
            assertFalse(Files.exists(out.resolve("emacs-mcp-server/index/index.html")),
                    "production=" + production + ": no page one level deeper");
            assertTrue(Files.isRegularFile(out.resolve("emacs-mcp-server/tutorial/index.html")),
                    "production=" + production + ": a sibling page is unaffected");

            deleteTree(proj);
        }
    }

    private void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }
}
