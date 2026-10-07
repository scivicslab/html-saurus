package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A built page addresses the portal's API by the project's directory name, not by the title shown
 * in the navbar.
 *
 * <p>The portal resolves a project by the directory it lives in. A page carried the navbar title
 * instead, so every project whose title differs from its directory name answered 404 to both of
 * the page's own controls: the Rebuild button posted
 * {@code /api/build-async/html/NIG%20Supercomputer} for a project the portal knows as
 * {@code nigsc_homepage2}, and the Source list asked {@code /api/source?path=NIG Supercomputer/docs/...}.
 * Projects whose title happens to equal their directory name, which is most of them, were unaffected.
 */
@Tag("S1")
class PageProjectNameTest {

    @TempDir
    Path tempDir;

    /** A project whose navbar title is a display name, as a published site has. */
    private Path createProject(String dirName, String navbarTitle) throws IOException {
        Path projectDir = tempDir.resolve(dirName);
        Files.createDirectories(projectDir.resolve("docs"));
        Files.writeString(projectDir.resolve("docusaurus.config.js"),
                "module.exports = { title: 'ignored', themeConfig: { navbar: { title: '"
                        + navbarTitle + "' } } };");
        Files.writeString(projectDir.resolve("docs/intro.md"),
                "---\ntitle: Intro\n---\n\nContent.\n");
        return projectDir;
    }

    @Test
    void builtPageNamesTheDirectoryNotTheNavbarTitle() throws IOException {
        Path proj = createProject("nigsc_homepage2", "NIG Supercomputer");
        Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

        String html = Files.readString(proj.resolve("static-html/intro.html"));

        assertTrue(html.contains("encodeURIComponent('nigsc_homepage2')"),
                "the Rebuild button must post the directory name");
        assertTrue(html.contains("data-path=\"nigsc_homepage2/docs/intro.md\""),
                "the Source list must ask for a path under the directory name");
        assertFalse(html.contains("encodeURIComponent('NIG Supercomputer')"),
                "the navbar title is not a project id");
    }

    @Test
    void theNavbarStillShowsTheTitle() throws IOException {
        Path proj = createProject("nigsc_homepage2", "NIG Supercomputer");
        Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

        String html = Files.readString(proj.resolve("static-html/intro.html"));

        assertTrue(html.contains(">NIG Supercomputer</a>"),
                "the reader still sees the site's own name on the bar");
    }
}
