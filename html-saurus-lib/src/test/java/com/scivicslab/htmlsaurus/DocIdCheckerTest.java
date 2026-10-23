package com.scivicslab.htmlsaurus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Which ids a project names that no document answers to, and how a portal is asked. */
@Tag("S1")
class DocIdCheckerTest {

    @TempDir
    Path proj;

    private void doc(String relative, String body) throws IOException {
        Path file = proj.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    @Test
    void reportsAnIdNoDocumentAnswersToWithEveryPlaceItIsWritten() throws Exception {
        doc("docs/a.md", "---\nid: A_260101_aa01\n---\n\nSee Known_260101_aa01 and Gone_260101_aa99.\n");
        doc("docs/sub/b.md", "Again Gone_260101_aa99 here.\n");
        DocIdChecker.Report report = DocIdChecker.check(proj, id -> id.startsWith("Known"));
        assertEquals(2, report.idsChecked());
        assertEquals(1, report.unknown().size());
        assertEquals("Gone_260101_aa99", report.unknown().get(0).id());
        assertEquals(List.of("docs/a.md:5", "docs/sub/b.md:1"), report.unknown().get(0).places());
    }

    @Test
    void askTheResolverOnceForAnIdWrittenManyTimes() throws Exception {
        doc("docs/a.md", "Dup_260101_aa01 and Dup_260101_aa01\n");
        doc("docs/b.md", "Dup_260101_aa01\n");
        List<String> asked = new ArrayList<>();
        DocIdChecker.check(proj, id -> { asked.add(id); return true; });
        assertEquals(List.of("Dup_260101_aa01"), asked);
    }

    @Test
    void localeDocsAreReadToo() throws Exception {
        doc("i18n/en/docusaurus-plugin-content-docs/current/x.md", "See Gone_260101_aa99.\n");
        DocIdChecker.Report report = DocIdChecker.check(proj, id -> false);
        assertEquals("i18n/en/docusaurus-plugin-content-docs/current/x.md:1",
                report.unknown().get(0).places().get(0));
    }

    @Test
    void hiddenDirectoriesAreNotRead() throws Exception {
        doc("docs/.rst-on-lisp/x.md", "Gone_260101_aa99\n");
        assertEquals(0, DocIdChecker.check(proj, id -> false).idsChecked());
    }

    @Test
    void aResolverThatCannotAnswerStopsTheCheck() throws Exception {
        doc("docs/a.md", "Some_260101_aa01\n");
        assertThrows(java.io.UncheckedIOException.class, () -> DocIdChecker.check(proj, id -> {
            throw new IOException("portal is down");
        }));
    }

    @Test
    void thePortalResolverReadsOkAsKnownAndNotFoundAsUnknown() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Set<String> known = Set.of("Known_260101_aa01");
        server.createContext("/api/resolve", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            String id = q.substring("id=".length());
            int status = id.equals("Broken_260101_aa01") ? 500 : known.contains(id) ? 200 : 404;
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
        try {
            PortalResolver portal = new PortalResolver("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertTrue(portal.exists("Known_260101_aa01"));
            assertFalse(portal.exists("Gone_260101_aa99"));
            assertThrows(IOException.class, () -> portal.exists("Broken_260101_aa01"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aPortalThatIsNotThereIsAnErrorAndNotAnUnknownId() {
        PortalResolver nobody = new PortalResolver("http://127.0.0.1:1");
        assertThrows(IOException.class, () -> nobody.exists("Any_260101_aa01"));
    }
}
