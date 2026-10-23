package com.scivicslab.htmlsaurus;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Three behaviours for a broken same-site link in a built page, chosen by two options: the default
 * warns and carries on, {@code --strict-links} fails the build, {@code --ignore-links} does not
 * look. The build reports its outcome through its return value, so a caller can stop before the
 * stages that read the pages.
 */
@Tag("S1")
class LinkPolicyTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreDefault() {
        BuildStages.linkPolicy(LinkPolicy.WARN);
    }

    private static CommandLine parse(String... args) throws Exception {
        Options opts = new Options();
        LinkPolicy.addOptions(opts);
        return new DefaultParser().parse(opts, args);
    }

    private Path project(String body) throws IOException {
        Path proj = tempDir.resolve("proj");
        Files.createDirectories(proj.resolve("docs"));
        Files.writeString(proj.resolve("docusaurus.config.js"), "module.exports = {};");
        Files.writeString(proj.resolve("docs/intro.md"),
                "---\ntitle: Intro\nid: intro\n---\n\n# Intro\n\n" + body + "\n");
        return proj;
    }

    /** What the build wrote to standard error, and whether it succeeded. */
    private record Run(boolean succeeded, String err) {}

    private Run build(Path proj) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            boolean ok = BuildStages.build(proj.resolve("docs"), proj.resolve("static-html"), true);
            return new Run(ok, captured.toString());
        } finally {
            System.setErr(original);
        }
    }

    private static final String BROKEN = "See <a href=\"/nowhere/\">nothing</a>.";

    @Test
    void withNeitherOptionTheChoiceIsToWarn() throws Exception {
        assertEquals(LinkPolicy.WARN, LinkPolicy.from(parse()));
    }

    @Test
    void strictLinksChoosesToFail() throws Exception {
        assertEquals(LinkPolicy.FAIL, LinkPolicy.from(parse("--strict-links")));
    }

    @Test
    void ignoreLinksChoosesToIgnore() throws Exception {
        assertEquals(LinkPolicy.IGNORE, LinkPolicy.from(parse("--ignore-links")));
    }

    @Test
    void bothOptionsAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> LinkPolicy.from(parse("--strict-links", "--ignore-links")));
    }

    @Test
    void warningLetsTheBuildSucceedAndNamesTheLink() throws Exception {
        BuildStages.linkPolicy(LinkPolicy.WARN);
        Run run = build(project(BROKEN));
        assertTrue(run.succeeded());
        assertTrue(run.err().contains("broken internal link"), run.err());
        assertTrue(run.err().contains("/nowhere/"), run.err());
    }

    @Test
    void strictFailsTheBuildAndSaysWhy() throws Exception {
        BuildStages.linkPolicy(LinkPolicy.FAIL);
        Run run = build(project(BROKEN));
        assertFalse(run.succeeded());
        assertTrue(run.err().contains("/nowhere/"), run.err());
        assertTrue(run.err().contains("Build failed"), run.err());
    }

    @Test
    void ignoringDoesNotLookAndSucceeds() throws Exception {
        BuildStages.linkPolicy(LinkPolicy.IGNORE);
        Run run = build(project(BROKEN));
        assertTrue(run.succeeded());
        assertFalse(run.err().contains("/nowhere/"), run.err());
        assertFalse(run.err().contains("broken internal link"), run.err());
    }

    @Test
    void strictLetsACleanSiteThrough() throws Exception {
        BuildStages.linkPolicy(LinkPolicy.FAIL);
        assertTrue(build(project("No links here.")).succeeded());
    }
}
