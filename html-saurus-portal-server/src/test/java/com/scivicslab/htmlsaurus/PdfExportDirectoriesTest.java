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
 * that hold at least two documents.
 */
@Tag("ExportToPdf_261119_oo01")
class PdfExportDirectoriesTest {

    @TempDir
    Path docs;

    private void document(String relative) throws IOException {
        Path file = docs.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ntitle: t\n---\n\nbody\n");
    }

    @Test
    void listDirectoriesToOffer_whenADirectoryHoldsOnlyOneDocument_doesNotOfferIt() throws IOException {
        document("AAS-G1/CHM13/010_Plan/010_Plan.md");
        document("AAS-G1/CHM13/020_Index/020_Index.md");
        document("AAS-G1/GRCh38/010_Login/010_Login.md");
        assertEquals(List.of("", "AAS-G1", "AAS-G1/CHM13"), PdfExportDirectories.listDirectoriesToOffer(docs));
    }

    @Test
    void listDirectoriesToOffer_withDirectoriesBuiltButNotDocuments_doesNotCountThem() throws IOException {
        document("a/one.md");
        document("a/_draft/two.md");
        document("a/.hidden/three.md");
        Files.createDirectories(docs.resolve("a/img"));
        Files.writeString(docs.resolve("a/img/fig.png"), "x");
        assertEquals(List.of(""), PdfExportDirectories.listDirectoriesToOffer(docs));
    }

    @Test
    void listDirectoriesToOffer_whenDocsDoesNotExist_offersNothing() throws IOException {
        assertEquals(List.of(), PdfExportDirectories.listDirectoriesToOffer(docs.resolve("missing")));
    }
}
