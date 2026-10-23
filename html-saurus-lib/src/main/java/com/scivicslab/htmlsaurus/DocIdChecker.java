package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Finds the document ids a project's Markdown names that no document answers to.
 *
 * <p>The ids are read from the sources by {@link DocIdScanner}. Whether an id has a document is
 * asked of a {@link Resolver}, which in use is a running portal ({@link PortalResolver}), because
 * the portal already knows every id across all the {@code doc_*} repositories and answers the
 * same way a reader's search would.</p>
 */
public final class DocIdChecker {

    private DocIdChecker() {}

    /** Answers whether some document declares this id. */
    public interface Resolver {
        /**
         * @return whether a document has this id
         * @throws IOException when the answer could not be had; this is not the same as "no"
         */
        boolean exists(String id) throws IOException;
    }

    /**
     * An id nothing answers to.
     *
     * @param id     the id as written
     * @param places where it is written, each as {@code <path under the project>:<line>}
     */
    public record Unknown(String id, List<String> places) {}

    /** What a check looked at, and what it did not find an answer for. */
    public record Report(int idsChecked, List<Unknown> unknown) {}

    /**
     * Checks every id the project's documents name.
     *
     * @param projectDir the Docusaurus project
     * @param resolver   the source of truth for which ids exist
     * @throws IOException when the sources cannot be read, or the resolver cannot answer
     */
    public static Report check(Path projectDir, Resolver resolver) throws IOException {
        Path root = projectDir.toAbsolutePath().normalize();
        Map<String, List<String>> places = new LinkedHashMap<>();
        for (Path file : sources(root)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String rel = root.relativize(file).toString().replace('\\', '/');
            for (DocIdScanner.Mention m : DocIdScanner.scan(text)) {
                places.computeIfAbsent(m.id(), k -> new ArrayList<>()).add(rel + ":" + m.line());
            }
        }
        // Sorted, so two runs over the same sources print the same list.
        Map<String, Unknown> unknown = new TreeMap<>();
        Map<String, Boolean> answered = new HashMap<>();
        for (Map.Entry<String, List<String>> e : places.entrySet()) {
            boolean exists = answered.computeIfAbsent(e.getKey(), id -> {
                try {
                    return resolver.exists(id);
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
            if (!exists) unknown.put(e.getKey(), new Unknown(e.getKey(), e.getValue()));
        }
        return new Report(places.size(), new ArrayList<>(unknown.values()));
    }

    /** The Markdown a project's pages are built from: its docs and each locale's docs. */
    private static List<Path> sources(Path root) throws IOException {
        List<Path> dirs = new ArrayList<>();
        dirs.add(root.resolve("docs"));
        Path i18n = root.resolve("i18n");
        if (Files.isDirectory(i18n)) {
            try (Stream<Path> locales = Files.list(i18n)) {
                locales.sorted().forEach(l -> dirs.add(l.resolve("docusaurus-plugin-content-docs/current")));
            }
        }
        List<Path> files = new ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !HiddenPaths.isHiddenUnder(dir, p))
                    .filter(p -> {
                        String s = p.toString();
                        return !s.contains("/static-html/") && !s.contains("/build/")
                                && !s.contains("/node_modules/");
                    })
                    .sorted()
                    .forEach(files::add);
            }
        }
        return files;
    }
}
