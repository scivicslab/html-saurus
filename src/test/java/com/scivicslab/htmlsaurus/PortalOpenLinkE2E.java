package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that clicking the project name link on a portal row loads the project
 * into the portal's right-pane iframe (staying in a single tab), rather than opening
 * a new browser tab. Right-click / Ctrl-click still open a real new tab because the
 * link stays a genuine anchor; that path is not exercised here.
 */
@Tag("S3")
class PortalOpenLinkE2E {

    @TempDir
    Path tempDir;

    @Test
    void projectLinkShouldLoadInRightPane() throws Exception {
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
        PortalServer portalServer = new PortalServer(tempDir, List.of(projectDir), 0, false, null);
        HttpServer server = portalServer.start();
        int port = server.getAddress().getPort();
        String portalUrl = "http://localhost:" + port + "/";

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext();
            Page portalPage = context.newPage();
            portalPage.navigate(portalUrl);

            // Verify the portal loaded and shows the project row with a clickable name
            portalPage.waitForSelector(".project-name a.project-link");
            assertEquals(1, portalPage.locator(".project-name a.project-link").count(),
                    "Expected exactly one project name link on the portal");

            // A plain left-click must load the project into the right-pane iframe,
            // NOT open a new browser tab.
            int pagesBefore = context.pages().size();
            portalPage.locator(".project-name a.project-link").first().click();
            portalPage.waitForTimeout(500);

            // No new browser tab must have opened.
            assertEquals(pagesBefore, context.pages().size(),
                    "Clicking a project link must not open a new browser tab");

            // The portal (top window) must stay put.
            assertEquals(portalUrl, portalPage.url(),
                    "Portal page should remain open after clicking a project link");

            // The right-pane iframe must now point at the project.
            String frameSrc = portalPage.locator("#doc-frame").getAttribute("src");
            assertTrue(frameSrc != null && frameSrc.contains("/myproject/"),
                    "Right-pane iframe src should contain /myproject/, was: " + frameSrc);
        } finally {
            server.stop(0);
        }
    }
}
