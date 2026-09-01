package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that a browser can actually get an icon for these pages, by both routes it has.
 *
 * <p>Named {@code ...Test} rather than {@code ...E2E} so that it runs: Surefire's default
 * patterns are {@code Test*}/{@code *Test}/{@code *Tests}/{@code *TestCase}, and this project
 * configures no others, so the existing {@code *E2E} classes only run when named on the command
 * line.</p>
 *
 * <p>A page states its icon inline as a {@code data:} URL, and a browser that does not use that
 * statement asks the origin for {@code /favicon.ico} instead. Serving neither leaves the tab
 * blank, and the two failures look identical from outside — hence one test covering both. The
 * declared icon is checked by handing its URL to the browser's own image decoder, so a truncated
 * or malformed data URL fails here rather than silently showing nothing.</p>
 */
@Tag("S3")
class FaviconE2ETest {

    /** A 1x1 PNG: enough for the icon pipeline, which only reads and re-encodes the bytes. */
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @TempDir
    Path tempDir;

    @Test
    void everyPageOffersAnIconTheBrowserCanDecode() throws Exception {
        Path projectDir = tempDir.resolve("myproject");
        Path docsDir = projectDir.resolve("docs");
        Files.createDirectories(docsDir);
        Files.createDirectories(projectDir.resolve("static/img"));
        Files.write(projectDir.resolve("static/img/favicon.png"), ONE_PIXEL_PNG);
        Files.writeString(projectDir.resolve("docusaurus.config.js"),
                "module.exports = { favicon: 'img/favicon.png' };");
        Files.writeString(docsDir.resolve("intro.md"),
                "---\nid: Intro_260902_oo01\ntitle: Intro\n---\n\n# Intro\n\nBody.\n");

        Path staticHtml = projectDir.resolve("static-html");
        new SiteBuilder(docsDir, staticHtml).build();

        PortalServer portalServer = new PortalServer(tempDir, List.of(projectDir), 0, false, null, 0);
        HttpServer server = portalServer.start();
        int port = server.getAddress().getPort();
        String origin = "http://localhost:" + port;

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newContext().newPage();

            // ── The portal page ──────────────────────────────────────────────
            page.navigate(origin + "/");
            assertDeclaredIconDecodes(page, "portal page");

            // ── /favicon.ico, the route a browser falls back to ──────────────
            Object status = page.evaluate(
                    "async () => {"
                  + "  const r = await fetch('/favicon.ico');"
                  + "  if (!r.ok) return 'HTTP ' + r.status;"
                  + "  const type = r.headers.get('content-type') || '';"
                  + "  const bytes = new Uint8Array(await r.arrayBuffer());"
                  + "  const png = bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4E && bytes[3] === 0x47;"
                  + "  return type + '|' + bytes.length + '|' + png;"
                  + "}");
            String[] parts = String.valueOf(status).split("\\|");
            assertEquals(3, parts.length, "/favicon.ico did not answer with an image: " + status);
            assertTrue(parts[0].startsWith("image/"),
                    "/favicon.ico must be served as an image, was: " + parts[0]);
            assertTrue(Integer.parseInt(parts[1]) > 0, "/favicon.ico was empty");
            assertEquals("true", parts[2], "/favicon.ico did not carry PNG bytes");

            // ── A generated document page, served through the portal ─────────
            page.navigate(origin + "/myproject/Intro_260902_oo01.html");
            assertDeclaredIconDecodes(page, "document page");

            browser.close();
        } finally {
            server.stop(0);
        }
    }

    /**
     * Asserts the page states exactly one icon, and that the browser can turn it into an image.
     *
     * @param page  the loaded page
     * @param where which page this is, for the failure message
     */
    private static void assertDeclaredIconDecodes(Page page, String where) {
        int links = page.locator("link[rel~=\"icon\"]").count();
        assertEquals(1, links, where + " should state exactly one icon, found " + links);

        Object result = page.evaluate(
                "async () => {"
              + "  const link = document.querySelector('link[rel~=\"icon\"]');"
              + "  if (!link) return 'no link';"
              + "  const href = link.getAttribute('href');"
              + "  if (!href || href === 'data:,') return 'empty href: ' + href;"
              + "  return await new Promise(res => {"
              + "    const im = new Image();"
              + "    im.onload = () => res('ok ' + im.naturalWidth + 'x' + im.naturalHeight);"
              + "    im.onerror = () => res('undecodable');"
              + "    im.src = href;"
              + "  });"
              + "}");
        assertTrue(String.valueOf(result).startsWith("ok "),
                where + "'s icon could not be decoded by the browser: " + result);
    }
}
