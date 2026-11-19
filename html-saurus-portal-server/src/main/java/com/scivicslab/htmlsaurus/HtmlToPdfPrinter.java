package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Prints an HTML file to a PDF the way a browser shows it (ExportToPdf_261119_oo01). {@link ChromePrinter} is the
 * implementation that runs; a test gives {@link PdfExportJob} a stand-in.
 */
@FunctionalInterface
interface HtmlToPdfPrinter {

    /**
     * Prints {@code html} to {@code pdf}.
     *
     * @param html    the file to print
     * @param pdf     where the PDF is written; an existing file is replaced
     * @param workDir a directory the printer may keep its own files in
     * @param timeout how long the printing may take
     * @throws IOException          when printing fails, runs past {@code timeout}, or writes no PDF
     * @throws InterruptedException when the thread is interrupted while it waits; the interrupt flag is set again
     */
    void printHtmlToPdf(Path html, Path pdf, Path workDir, Duration timeout) throws IOException, InterruptedException;
}
