package com.scivicslab.yadoc;

import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that clicking "Open" on a portal project card opens the project
 * in a NEW tab, not in the same tab (which would hide the portal).
 */
class PortalOpenLinkTest {

    @TempDir
    Path tempDir;

    @Test
    void openButtonShouldOpenInNewTab() throws Exception {
        // --- Arrange: fake Docusaurus project structure ---
        Path projectDir = tempDir.resolve("myproject");
        Files.createDirectories(projectDir.resolve("docs"));
        Files.writeString(projectDir.resolve("docusaurus.config.js"), "module.exports = {};");
        // static-html must exist so the server can resolve the route
        Path staticHtml = projectDir.resolve("static-html");
        Files.createDirectories(staticHtml);
        Files.writeString(staticHtml.resolve("index.html"),
                "<html><body><h1>myproject</h1></body></html>");

        // --- Start portal server on a random port (port 0) ---
        PortalServer portalServer = new PortalServer(List.of(projectDir), 0);
        HttpServer server = portalServer.start();
        int port = server.getAddress().getPort();
        String portalUrl = "http://localhost:" + port + "/";

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext();
            Page portalPage = context.newPage();
            portalPage.navigate(portalUrl);

            // Verify the portal loaded and shows the project card
            portalPage.waitForSelector(".btn-open");
            assertEquals(1, portalPage.locator(".btn-open").count(),
                    "Expected exactly one Open button on the portal");

            // Click "Open" and expect a NEW tab to appear
            // If target="_blank" is missing, the current page navigates away
            // and waitForPage() will throw (timeout) — causing the test to fail.
            Page newTab = context.waitForPage(
                    new BrowserContext.WaitForPageOptions().setTimeout(3000),
                    () -> portalPage.locator(".btn-open").first().click());

            // Portal page must still show the portal (not navigated away)
            assertEquals(portalUrl, portalPage.url(),
                    "Portal page should remain open after clicking Open");

            // New tab should point to the project
            assertTrue(newTab.url().contains("/myproject/"),
                    "New tab URL should contain /myproject/, was: " + newTab.url());
        } finally {
            server.stop(0);
        }
    }
}
