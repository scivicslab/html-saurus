package com.scivicslab.htmlsaurus;

/**
 * What an import has produced so far, whatever its source: the newest Markdown file written (as
 * {@code "<project>/docs/<destPath>/<stem>/<stem>.md"}, the form the Import screen displays), and
 * how many image files were written alongside.
 *
 * <p>Shared by every job kind on {@code PortalServer}'s one import {@code JobRegistry}
 * ({@link PdfImportJob}, {@link VideoImportJob}), so the Batch Job list can read any of them
 * without knowing which kind produced it.
 */
record ImportOutcome(String lastFile, int totalImages) {

    /** An import that has not written anything yet. */
    static final ImportOutcome NOTHING_YET = new ImportOutcome("", 0);
}
