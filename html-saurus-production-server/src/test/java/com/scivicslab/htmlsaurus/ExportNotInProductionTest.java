package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Export tab is for the development server only (ExportToPdf_261119_oo01). It starts Chrome on the machine and
 * rebuilds a project's HTML on a request that asks for no credential, so a public site must not have it. The classes
 * that do this live in the portal server's jar; the production server's classpath must not reach them.
 */
@Tag("ExportToPdf_261119_oo01")
class ExportNotInProductionTest {

    @Test
    @DisplayName("The export classes are not on the production server's classpath")
    void exportClasses_areNotOnTheProductionClasspath() {
        for (String name : List.of("PdfExportJob", "PdfExportHtml", "PdfExportPlan", "PdfExportFinish",
                                   "PdfExportDirectories", "ChromePrinter", "ExportOutcome")) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("com.scivicslab.htmlsaurus." + name),
                    name + " is on the production server's classpath, so it ships in its jar");
        }
    }
}
