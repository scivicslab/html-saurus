package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * The directories of a doc project that the Export tab offers (ExportToPdf_261119_oo01): the whole project first, then
 * every directory under {@code docs/} that an export would make a PDF of at least two documents from. The count comes
 * from the sidebar tree, the tree the export itself plans from, so a directory the sidebar leaves out, or one that is
 * only a single document's own folder, is not offered.
 */
final class PdfExportDirectories {

    private PdfExportDirectories() {}

    /** A directory is offered when its export holds this many documents. */
    static final int MINIMUM_DOCUMENTS = 2;

    /**
     * Lists the directories to offer.
     *
     * @param docsDirectory the project's {@code docs/} directory
     * @param sidebarTree   the project's sidebar tree ({@code NavTreeBuilder#build})
     * @return paths relative to {@code docs/}, with {@code /} as the separator; the first is {@code ""}, the whole
     *         project, and the others are in path order
     * @throws IOException when a directory cannot be read
     */
    static List<String> listDirectoriesToOffer(Path docsDirectory, SiteNode sidebarTree) throws IOException {
        List<String> offered = new ArrayList<>();
        if (!Files.isDirectory(docsDirectory)) return offered;
        offered.add("");
        List<String> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(docsDirectory)) {
            for (Path directory : (Iterable<Path>) walk.filter(Files::isDirectory)::iterator) {
                Path relative = docsDirectory.relativize(directory);
                if (relative.toString().isEmpty() || isHidden(relative)) continue;
                String path = relative.toString().replace('\\', '/');
                if (PdfExportPlan.planOfDirectory(sidebarTree, path, "").documentCount() >= MINIMUM_DOCUMENTS) {
                    found.add(path);
                }
            }
        }
        Collections.sort(found);
        offered.addAll(found);
        return offered;
    }

    /** @return true when a segment of the path starts with {@code _} or {@code .}, which the site does not build */
    private static boolean isHidden(Path relative) {
        for (Path segment : relative) {
            String name = segment.toString();
            if (name.startsWith("_") || name.startsWith(".")) return true;
        }
        return false;
    }
}
