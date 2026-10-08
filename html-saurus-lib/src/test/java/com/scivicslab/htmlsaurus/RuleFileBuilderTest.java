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
 * Checks when the rule-file step runs the external tool and when it stays out of the way.
 *
 * <p>A stub script stands in for {@code make-markdown}: it records the directory it was given, so the
 * test can tell whether it ran at all and with what.</p>
 */
class RuleFileBuilderTest {

    /** Writes an executable script that appends its first argument to {@code record}. */
    private Path stubTool(Path dir, Path record) throws IOException {
        Path tool = dir.resolve("make-markdown");
        Files.writeString(tool, "#!/bin/bash\necho \"$1\" >> \"" + record + "\"\necho ran\n");
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwxr-xr-x"));
        return tool;
    }

    @Test
    void runsTheToolWhenARuleFileSitsBesideItsMarkdown(@TempDir Path dir) throws IOException {
        Path docs = Files.createDirectories(dir.resolve("docs/some/page"));
        Files.writeString(docs.resolve("page.lisp"), "(in-package :rst)\n");
        Files.writeString(docs.resolve("page.md"), "# page\n");
        Path record = dir.resolve("record.txt");
        System.setProperty("html-saurus.rule-file-tool", stubTool(dir, record).toString());

        RuleFileBuilder.rebuild(dir.resolve("docs"));

        assertTrue(Files.exists(record), "the tool should have run");
        assertEquals(dir.resolve("docs").toString(), Files.readString(record).strip());
    }

    @Test
    void doesNotRunTheToolForALispFileThatIsOnlyACodeBlock(@TempDir Path dir) throws IOException {
        Path docs = Files.createDirectories(dir.resolve("docs/some/page"));
        Files.writeString(docs.resolve("page.md"), "# page\n");
        Files.writeString(docs.resolve("zentai_2.lisp"), "(defrule x -> \"y\")\n");
        Path record = dir.resolve("record.txt");
        System.setProperty("html-saurus.rule-file-tool", stubTool(dir, record).toString());

        RuleFileBuilder.rebuild(dir.resolve("docs"));

        assertFalse(Files.exists(record),
                "a .lisp without a .md of the same base name is a code block, not a rule file");
    }

    @Test
    void doesNotRunTheToolWhenThereIsNoLispFile(@TempDir Path dir) throws IOException {
        Path docs = Files.createDirectories(dir.resolve("docs/some/page"));
        Files.writeString(docs.resolve("page.md"), "# written by hand\n");
        Path record = dir.resolve("record.txt");
        System.setProperty("html-saurus.rule-file-tool", stubTool(dir, record).toString());

        RuleFileBuilder.rebuild(dir.resolve("docs"));

        assertFalse(Files.exists(record), "the tool should not run when no rule file exists");
    }

    @Test
    void survivesAMissingTool(@TempDir Path dir) throws IOException {
        Path docs = Files.createDirectories(dir.resolve("docs"));
        Files.writeString(docs.resolve("page.lisp"), "(in-package :rst)\n");
        Files.writeString(docs.resolve("page.md"), "# page\n");
        System.setProperty("html-saurus.rule-file-tool", dir.resolve("not-installed").toString());

        RuleFileBuilder.rebuild(docs);   // must not throw: stale Markdown may not stop the site build
    }
}
