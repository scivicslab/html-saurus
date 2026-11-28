package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins {@link FeedGenerator#gitLastModifiedIso}: the commit time of a committed file, and {@code null} when the directory is missing. */
class GitLastModifiedTest {

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 5];
        cmd[0] = "git";
        cmd[1] = "-c";
        cmd[2] = "user.name=test";
        cmd[3] = "-c";
        cmd[4] = "user.email=test@example.invalid";
        System.arraycopy(args, 0, cmd, 5, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), String.join(" ", args));
    }

    @Test
    void aCommittedFileGivesItsCommitTimeAsRfc3339(@TempDir Path dir) throws Exception {
        git(dir, "init", "-q");
        Path file = dir.resolve("a.md");
        Files.writeString(file, "# a\n");
        git(dir, "add", "a.md");
        git(dir, "commit", "-q", "-m", "a");

        String iso = FeedGenerator.gitLastModifiedIso(file);

        assertNotNull(iso);
        assertTrue(iso.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}([+-]\\d{2}:\\d{2}|Z)"), iso);
    }

    @Test
    void aFileWhoseDirectoryDoesNotExistGivesNull(@TempDir Path dir) {
        assertNull(FeedGenerator.gitLastModifiedIso(dir.resolve("missing/d.md")));
    }
}
