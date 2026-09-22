package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks when the diagram step runs the external tool and when it stays out of the way.
 *
 * <p>A stub script stands in for {@code make-diagrams}: it records the directory it was given, so
 * the test can tell whether it ran at all and with what.</p>
 */
class DiagramBuilderTest {

    /** Writes an executable script that appends its first argument to {@code record}. */
    private Path stubTool(Path dir, Path record) throws IOException {
        Path tool = dir.resolve("make-diagrams");
        Files.writeString(tool, "#!/bin/bash\necho \"$1\" >> \"" + record + "\"\necho ran\n");
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwxr-xr-x"));
        return tool;
    }

    @Test
    void runsTheToolWhenADocsDirectoryHasAFigure(@TempDir Path dir) throws IOException {
        Path docs = Files.createDirectories(dir.resolve("docs/some/page"));
        Files.writeString(docs.resolve("actor-tree.jsh"), "// a figure\n");
        Path record = dir.resolve("record.txt");
        System.setProperty("html-saurus.diagram-tool", stubTool(dir, record).toString());

        DiagramBuilder.rebuild(dir.resolve("docs"));

        assertTrue(Files.exists(record), "the tool should have run");
        assertEquals(dir.resolve("docs").toString(), Files.readString(record).strip());
    }

    @Test
    void doesNotRunTheToolWhenThereIsNoFigure(@TempDir Path dir) throws IOException {
        Path docs = Files.createDirectories(dir.resolve("docs/some/page"));
        Files.writeString(docs.resolve("page.md"), "# no figure here\n");
        Path record = dir.resolve("record.txt");
        System.setProperty("html-saurus.diagram-tool", stubTool(dir, record).toString());

        DiagramBuilder.rebuild(dir.resolve("docs"));

        assertFalse(Files.exists(record), "the tool should not run when no .jsh exists");
    }

    @Test
    void survivesAMissingTool(@TempDir Path dir) throws IOException {
        Path docs = Files.createDirectories(dir.resolve("docs"));
        Files.writeString(docs.resolve("actor-tree.jsh"), "// a figure\n");
        System.setProperty("html-saurus.diagram-tool", dir.resolve("not-installed").toString());

        DiagramBuilder.rebuild(docs);   // must not throw: a stale figure may not stop the site build
    }
}
