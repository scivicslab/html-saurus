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
 * every directory under {@code docs/} that holds at least two documents, so a directory that is only one document's
 * own folder is not offered.
 */
final class PdfExportDirectories {

    private PdfExportDirectories() {}

    /** A directory is offered when this many documents are under it. */
    static final int MINIMUM_DOCUMENTS = 2;

    /**
     * Lists the directories to offer.
     *
     * @param docsDirectory the project's {@code docs/} directory
     * @return paths relative to {@code docs/}, with {@code /} as the separator; the first is {@code ""}, the whole
     *         project, and the others are in path order
     * @throws IOException when a directory cannot be read
     */
    static List<String> listDirectoriesToOffer(Path docsDirectory) throws IOException {
        List<String> offered = new ArrayList<>();
        if (!Files.isDirectory(docsDirectory)) return offered;
        offered.add("");
        List<String> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(docsDirectory)) {
            for (Path directory : (Iterable<Path>) walk.filter(Files::isDirectory)::iterator) {
                Path relative = docsDirectory.relativize(directory);
                if (relative.toString().isEmpty() || isHidden(relative)) continue;
                if (countDocuments(directory) >= MINIMUM_DOCUMENTS) {
                    found.add(relative.toString().replace('\\', '/'));
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

    private static int countDocuments(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            return (int) walk.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !isHidden(directory.relativize(p)))
                    .count();
        }
    }
}
