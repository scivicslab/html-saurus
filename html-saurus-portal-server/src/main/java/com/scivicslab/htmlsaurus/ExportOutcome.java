package com.scivicslab.htmlsaurus;

import java.nio.file.Path;

/**
 * What an export left behind, carried on its job so the browser can download it (ExportToPdf_261119_oo01).
 *
 * @param workDirectory the directory the export works in and keeps its PDF in; {@code Clear} deletes it
 * @param pdf       the PDF, or {@code null} while the export is still running or when it did not finish
 * @param pages     its page count
 * @param documents how many documents it holds
 */
record ExportOutcome(Path workDirectory, Path pdf, int pages, int documents) {

    /** @return the outcome of an export that has started and has no PDF yet */
    static ExportOutcome startedIn(Path workDirectory) {
        return new ExportOutcome(workDirectory, null, 0, 0);
    }
}
