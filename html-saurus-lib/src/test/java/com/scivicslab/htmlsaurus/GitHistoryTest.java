package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Pins {@link GitHistory#lastCommit}: git's answer for a committed file, and {@code null}, never git's error text, otherwise. */
class GitHistoryTest {

    private static void git(Path dir, String... args) throws Exception {
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
    void aCommittedFileGivesTheRequestedFormat(@TempDir Path dir) throws Exception {
        git(dir, "init", "-q");
        Path file = dir.resolve("a.md");
        Files.writeString(file, "# a\n");
        git(dir, "add", "a.md");
        git(dir, "commit", "-q", "-m", "a");

        assertTrue(GitHistory.lastCommit(file, "--format=%ad", "--date=short").matches("\\d{4}-\\d{2}-\\d{2}"));
        assertEquals("a", GitHistory.lastCommit(file, "--format=%s"));
    }

    @Test
    void anUntrackedFileInARepositoryGivesNullNotGitsErrorText(@TempDir Path dir) throws Exception {
        git(dir, "init", "-q");
        Path file = dir.resolve("b.md");
        Files.writeString(file, "# b\n");

        assertNull(GitHistory.lastCommit(file, "--format=%aI"));
    }

    @Test
    void aFileOutsideAnyRepositoryGivesNullNotGitsErrorText(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("c.md");
        Files.writeString(file, "# c\n");

        assertNull(GitHistory.lastCommit(file, "--format=%aI"));
    }

    @Test
    void aFileWhoseDirectoryDoesNotExistGivesNull(@TempDir Path dir) {
        assertNull(GitHistory.lastCommit(dir.resolve("missing/d.md"), "--format=%aI"));
    }
}
