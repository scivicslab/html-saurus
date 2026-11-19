package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The work directories of exports whose jobs the registry has forgotten (ExportToPdf_261119_oo01): the registry forgets
 * a job an hour after it ended, and its PDF would stay in the temporary directory until the next start.
 */
@Tag("ExportToPdf_261119_oo01")
class ExportWorkDirectoriesTest {

    @TempDir
    Path tempDir;

    private Path directory(String name) throws IOException {
        Path d = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(d.resolve("proj.pdf"), "pdf");
        return d;
    }

    @Test
    void deleteOfForgottenJobs_deletesTheDirectoriesOfJobsTheRegistryNoLongerKnows_only() throws IOException {
        ExportWorkDirectories directories = new ExportWorkDirectories();
        Path known = directory("known");
        Path forgotten = directory("forgotten");
        directories.remember("job-known", known);
        directories.remember("job-forgotten", forgotten);

        int deleted = directories.deleteOfForgottenJobs(Set.of("job-known")::contains);

        assertEquals(1, deleted);
        assertTrue(Files.isDirectory(known), "the job is still listed, so its PDF can still be downloaded");
        assertFalse(Files.exists(forgotten), "the job was forgotten, nobody can download its PDF");
        assertEquals(0, directories.deleteOfForgottenJobs(Set.of("job-known")::contains), "a directory is deleted once");
    }

    @Test
    void forget_whenAJobIsCleared_leavesItsDirectoryToTheCaller() throws IOException {
        ExportWorkDirectories directories = new ExportWorkDirectories();
        Path cleared = directory("cleared");
        directories.remember("job", cleared);
        directories.forget("job");
        assertEquals(0, directories.deleteOfForgottenJobs(id -> false));
        assertTrue(Files.isDirectory(cleared));
    }
}
