package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which directories the Export tab offers (ExportToPdf_261119_oo01): the whole project first, then the directories
 * whose export holds at least two documents, counted in the sidebar tree.
 */
@Tag("ExportToPdf_261119_oo01")
class PdfExportDirectoriesTest {

    @TempDir
    Path docs;

    private void directory(String relative) throws IOException {
        Files.createDirectories(docs.resolve(relative));
    }

    private static SiteNode leaf(String title, String href) {
        return new SiteNode(title, href, false, List.of(), null);
    }

    private static SiteNode folder(String label, SiteNode... children) {
        return new SiteNode(label, null, true, List.of(children), null);
    }

    @Test
    void listDirectoriesToOffer_whenADirectoryHoldsOnlyOneDocument_doesNotOfferIt() throws IOException {
        directory("AAS-G1/CHM13/Plan");
        directory("AAS-G1/CHM13/Index");
        directory("AAS-G1/GRCh38/Login");
        SiteNode tree = folder("docs", folder("AAS-G1",
                folder("CHM13", leaf("Plan", "/AAS-G1/CHM13/Plan/"), leaf("Index", "/AAS-G1/CHM13/Index/")),
                folder("GRCh38", leaf("Login", "/AAS-G1/GRCh38/Login/"))));
        assertEquals(List.of("", "AAS-G1", "AAS-G1/CHM13"), PdfExportDirectories.listDirectoriesToOffer(docs, tree));
    }

    @Test
    void listDirectoriesToOffer_whenTheSidebarLeavesADirectoryOut_doesNotOfferIt() throws IOException {
        directory("AAS-G1/a");
        directory("AAS-G1/b");
        directory("mm/x");
        directory("mm/y");
        SiteNode tree = folder("docs", folder("AAS-G1", leaf("A", "/AAS-G1/a/"), leaf("B", "/AAS-G1/b/")));
        assertEquals(List.of("", "AAS-G1"), PdfExportDirectories.listDirectoriesToOffer(docs, tree));
    }

    @Test
    void listDirectoriesToOffer_withDirectoriesTheSiteDoesNotBuild_doesNotOfferThem() throws IOException {
        directory("a/_draft/one");
        directory("a/_draft/two");
        directory(".git/objects");
        // The sidebar tree has no node for them, as NavTreeBuilder leaves out names that start with _ or .
        SiteNode tree = folder("docs");
        assertEquals(List.of(""), PdfExportDirectories.listDirectoriesToOffer(docs, tree));
    }

    @Test
    void listDirectoriesToOffer_whenDocsDoesNotExist_offersNothing() throws IOException {
        assertEquals(List.of(), PdfExportDirectories.listDirectoriesToOffer(docs.resolve("missing"), folder("docs")));
    }
}
