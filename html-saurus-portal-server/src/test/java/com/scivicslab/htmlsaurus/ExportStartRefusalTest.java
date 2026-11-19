package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The requests {@code POST /api/export/pdf/start} refuses (ExportToPdf_261119_oo01): each gets an error that says why,
 * and none starts a job.
 */
@Tag("ExportToPdf_261119_oo01")
class ExportStartRefusalTest {

    @TempDir
    Path tempDir;

    private ExportPortalTestSupport portal;

    @AfterEach
    void close() {
        if (portal != null) portal.close();
    }

    private String base() throws Exception {
        portal = new ExportPortalTestSupport(tempDir);
        return portal.startPortal();
    }

    private void assertNoJobWasStarted(String base) throws Exception {
        assertEquals("[]", portal.get(base + "/api/import/jobs").body().strip(), "a refused request started a job");
    }

    @Test
    void start_whenTheDirectoryDoesNotExist_answers400WithTheReasonAndStartsNoJob() throws Exception {
        String base = base();
        HttpResponse<String> answer = portal.post(base + "/api/export/pdf/start", "{\"project\":\"proj\",\"dir\":\"no/such/directory\"}");
        assertEquals(400, answer.statusCode());
        assertTrue(answer.body().contains("no such directory") && answer.body().contains("no/such/directory"), answer.body());
        assertNoJobWasStarted(base);
    }

    @Test
    void start_whenTheDirectoryLeavesDocs_answers400AndStartsNoJob() throws Exception {
        String base = base();
        for (String dir : new String[] {"..", "../..", "A/../..", "/etc"}) {
            HttpResponse<String> answer = portal.post(base + "/api/export/pdf/start", "{\"project\":\"proj\",\"dir\":\"" + dir + "\"}");
            // A leading slash is dropped, so "/etc" asks for docs/etc, which does not exist; the others try to leave docs/.
            assertEquals(400, answer.statusCode(), dir + ": " + answer.body());
        }
        assertNoJobWasStarted(base);
    }

    @Test
    void start_whenNoChromeIsFound_answers500NamingTheSettingAndStartsNoJob() throws Exception {
        String base = base();
        portal.useChrome(tempDir.resolve("no-such-chrome"));
        HttpResponse<String> answer = portal.post(base + "/api/export/pdf/start", "{\"project\":\"proj\",\"dir\":\"A\"}");
        assertEquals(500, answer.statusCode());
        assertTrue(answer.body().contains("no Chrome") && answer.body().contains(ChromePrinter.PROPERTY), answer.body());
        assertNoJobWasStarted(base);
    }

    @Test
    void start_whenTheProjectIsUnknown_answers404AndStartsNoJob() throws Exception {
        String base = base();
        assertEquals(404, portal.post(base + "/api/export/pdf/start", "{\"project\":\"nope\",\"dir\":\"\"}").statusCode());
        assertEquals(404, portal.post(base + "/api/export/pdf/start", "{\"dir\":\"A\"}").statusCode());
        assertNoJobWasStarted(base);
    }

    @Test
    void start_whenTheMethodIsNotPost_answers405() throws Exception {
        String base = base();
        assertEquals(405, portal.get(base + "/api/export/pdf/start").statusCode());
        assertNoJobWasStarted(base);
    }
}
