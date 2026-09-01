package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that a generated page states an icon a browser can use.
 *
 * <p>A page states its icon inline as a {@code data:} URL. Getting that wrong is silent — the tab
 * simply shows nothing — so the bytes are decoded here and checked against the file signature of
 * the format the URL claims. Whether a browser then renders it is a separate question, asked by
 * {@code FaviconE2E} against a running deployment ({@code TestingStandard_260404_oo01}).</p>
 */
class FaviconTest {

    /** A 1x1 PNG: enough for the icon pipeline, which only reads and re-encodes the bytes. */
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    private static final Pattern ICON_LINK =
            Pattern.compile("<link[^>]*rel=\"[^\"]*icon[^\"]*\"[^>]*>");
    private static final Pattern DATA_URL =
            Pattern.compile("href=\"data:([^;\"]+);base64,([A-Za-z0-9+/=]+)\"");

    @TempDir
    Path tempDir;

    @Test
    void generatedPage_withFaviconConfigured_statesOneIconCarryingThatImage() throws Exception {
        Path html = buildOnePage("favicon: 'img/favicon.png'", "img/favicon.png", ONE_PIXEL_PNG);
        String page = Files.readString(html);

        Matcher links = ICON_LINK.matcher(page);
        assertTrue(links.find(), "the page states no icon at all");
        String link = links.group();
        assertFalse(links.find(), "the page states more than one icon; the browser picks one of them");

        Matcher url = DATA_URL.matcher(link);
        assertTrue(url.find(), "the icon is not an inline data URL: " + link);
        assertEquals("image/png", url.group(1), "the declared media type does not match the source file");

        byte[] bytes = Base64.getDecoder().decode(url.group(2));
        assertArrayEquals(ONE_PIXEL_PNG, bytes, "the icon's bytes are not the configured file's");
    }

    @Test
    void generatedPage_withoutFaviconConfigured_statesNoUsableIcon() throws Exception {
        Path html = buildOnePage("", null, null);
        String page = Files.readString(html);

        Matcher links = ICON_LINK.matcher(page);
        assertTrue(links.find(), "the page should still carry the link element");
        // "data:," is an empty resource: nothing to show, and nothing that could be mistaken for
        // a working icon by a reader of the HTML.
        assertTrue(links.group().contains("data:,"),
                "with no favicon configured the href should be the empty data URL, was: " + links.group());
    }

    @Test
    void icoFile_isDeclaredAsAnIconMediaTypeAndKeepsItsSignature() throws Exception {
        // An .ico carries its own signature (00 00 01 00) and must not be relabelled as a PNG.
        byte[] ico = new byte[] {0, 0, 1, 0, 1, 0, 16, 16, 0, 0, 1, 0, 32, 0, 0, 0, 0, 0};
        Path html = buildOnePage("favicon: 'img/favicon.ico'", "img/favicon.ico", ico);
        String page = Files.readString(html);

        Matcher url = DATA_URL.matcher(ICON_LINK.matcher(page).results().findFirst().orElseThrow().group());
        assertTrue(url.find(), "the icon is not an inline data URL");
        assertEquals("image/x-icon", url.group(1));
        assertArrayEquals(ico, Base64.getDecoder().decode(url.group(2)));
    }

    /**
     * Builds a one-page site and returns that page's HTML file.
     *
     * @param faviconLine the {@code favicon:} entry for the config, or empty for none
     * @param staticPath  where the icon lives under {@code static/}, or {@code null} for none
     * @param iconBytes   the icon's bytes, or {@code null} for none
     */
    private Path buildOnePage(String faviconLine, String staticPath, byte[] iconBytes) throws Exception {
        Path projectDir = tempDir.resolve("proj-" + faviconLine.hashCode());
        Path docsDir = projectDir.resolve("docs");
        Files.createDirectories(docsDir);
        if (staticPath != null) {
            Path icon = projectDir.resolve("static").resolve(staticPath);
            Files.createDirectories(icon.getParent());
            Files.write(icon, iconBytes);
        }
        Files.writeString(projectDir.resolve("docusaurus.config.js"),
                "module.exports = { " + faviconLine + " };");
        Files.writeString(docsDir.resolve("intro.md"),
                "---\nid: Intro_260902_oo01\ntitle: Intro\n---\n\n# Intro\n\nBody.\n");

        Path staticHtml = projectDir.resolve("static-html");
        new SiteBuilder(docsDir, staticHtml).build();
        return staticHtml.resolve("Intro_260902_oo01.html");
    }
}
