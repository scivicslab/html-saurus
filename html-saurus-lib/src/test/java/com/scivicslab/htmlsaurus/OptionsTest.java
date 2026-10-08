package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A program says which options it takes, and anything else is reported rather than swallowed.
 *
 * <p>What makes this worth a test is where an option goes when nobody declared it. Each program
 * used to walk its own chain of {@code else if}s ending in "does not begin with --, so it is the
 * directory". {@code --port 80} given to the build program matched no branch, and the {@code 80}
 * after it became the directory to build. Nothing was printed, and the build ran in {@code ./80}.
 */
@Tag("S1")
class OptionsTest {

    private ByteArrayOutputStream err;
    private PrintStream savedErr;

    @BeforeEach
    void captureStderr() {
        savedErr = System.err;
        err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(savedErr);
    }

    private String reported() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Options buildProgram(String... args) {
        return new Options("html-saurus-build-only")
                .flag("--html").flag("--all-projects")
                .value("--threads")
                .renamed("--portal-mode", "--all-projects")
                .obsolete("--production", false, "already the default")
                .obsolete("--port", true, "this program never starts a server")
                .parse(args);
    }

    @Test
    @DisplayName("An option the program does not take never becomes the directory")
    void aValueOfAnUndeclaredOptionIsNotTakenForThePath() {
        Options o = buildProgram("/data/site", "--port", "80");
        assertEquals(Path.of("/data/site"), o.path(),
                "the 80 after --port must not be read as the directory to build");
        assertTrue(reported().contains("--port"), "the reason must reach whoever typed it: " + reported());
    }

    @Test
    @DisplayName("An unknown option is reported and the rest of the line still works")
    void anUnknownOptionIsReported() {
        Options o = buildProgram("/data/site", "--rebuild-everything", "--html");
        assertTrue(o.is("--html"));
        assertEquals(Path.of("/data/site"), o.path());
        assertTrue(reported().contains("unknown option --rebuild-everything"), reported());
    }

    @Test
    @DisplayName("A second bare word is reported instead of replacing the first")
    void aSecondBareWordIsReported() {
        Options o = buildProgram("/data/site", "80");
        assertEquals(Path.of("/data/site"), o.path());
        assertTrue(reported().contains("unexpected argument 80"), reported());
    }

    @Test
    @DisplayName("A renamed option keeps working and says what it is called now")
    void aRenamedOptionStillActs() {
        Options o = buildProgram("/data/site", "--portal-mode");
        assertTrue(o.is("--all-projects"), "--portal-mode meant this, and must go on meaning it");
        assertTrue(reported().contains("--all-projects"), reported());
    }

    @Test
    @DisplayName("An option that no longer does anything says why")
    void anObsoleteOptionExplainsItself() {
        Options o = buildProgram("/data/site", "--production", "--html");
        assertTrue(o.is("--html"), "the rest of the line is unaffected");
        assertEquals(Path.of("/data/site"), o.path());
        assertTrue(reported().contains("--production"), reported());
        assertTrue(reported().contains("already the default"), reported());
    }

    @Test
    @DisplayName("A value option reads the word after it")
    void valueOptionsRead() {
        Options o = buildProgram("/data/site", "--threads", "8");
        assertEquals(8, o.number("--threads", 0));
        assertEquals(Path.of("/data/site"), o.path());
        assertEquals("", reported(), "nothing to report about a line that is entirely declared");
    }

    @Test
    @DisplayName("A value option with nothing after it is reported, not read past the end")
    void valueOptionAtTheEnd() {
        Options o = buildProgram("/data/site", "--threads");
        assertEquals(4, o.number("--threads", 4));
        assertTrue(reported().contains("needs an argument"), reported());
    }

    @Test
    @DisplayName("A value that is not a number is reported and the default stands")
    void nonNumericValue() {
        Options o = buildProgram("/data/site", "--threads", "many");
        assertEquals(4, o.number("--threads", 4));
        assertTrue(reported().contains("not a number"), reported());
    }

    @Test
    @DisplayName("With no bare word the directory is where the program was started")
    void noPathMeansHere() {
        Options o = buildProgram("--html");
        assertEquals(Path.of("").toAbsolutePath(), o.path());
    }
}
