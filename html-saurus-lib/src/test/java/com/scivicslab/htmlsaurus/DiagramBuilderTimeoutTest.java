package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins that DiagramBuilder stops waiting for a tool that does not finish, whether or not the tool has printed anything. */
class DiagramBuilderTimeoutTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("html-saurus.diagram-tool");
    }

    private Path docsWithStub(Path dir, String toolBody) throws IOException {
        Path docs = Files.createDirectories(dir.resolve("docs/page"));
        Files.writeString(docs.resolve("fig.jsh"), "// figure\n");
        Path tool = dir.resolve("make-diagrams");
        Files.writeString(tool, "#!/bin/bash\n" + toolBody + "\n");
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwxr-xr-x"));
        System.setProperty("html-saurus.diagram-tool", tool.toString());
        return dir.resolve("docs");
    }

    @Test
    void aToolThatPrintsNothingAndNeverEndsIsGivenUpOnWhenTheLimitPasses(@TempDir Path dir) throws IOException {
        Path docs = docsWithStub(dir, "sleep 60");
        long t0 = System.nanoTime();
        DiagramBuilder.rebuild(docs, Duration.ofMillis(700));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 15_000, "returned after " + ms + " ms, not after the tool's own sleep");
    }

    @Test
    void aToolThatPrintsAndThenNeverEndsIsGivenUpOnToo(@TempDir Path dir) throws IOException {
        Path docs = docsWithStub(dir, "echo started; sleep 60");
        long t0 = System.nanoTime();
        DiagramBuilder.rebuild(docs, Duration.ofMillis(700));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 15_000, "returned after " + ms + " ms, not after the tool's own sleep");
    }

    @Test
    void aToolThatEndsInTimeStillRunsToTheEnd(@TempDir Path dir) throws IOException {
        Path record = dir.resolve("record.txt");
        Path docs = docsWithStub(dir, "echo \"$1\" >> '" + record + "'");
        DiagramBuilder.rebuild(docs, Duration.ofSeconds(30));
        assertEquals(docs.toString(), Files.readString(record).strip());
    }
}
