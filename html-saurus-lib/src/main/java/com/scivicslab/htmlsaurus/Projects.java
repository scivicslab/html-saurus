package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the Markdown is: finding the Docusaurus projects under a directory, and reading out of a
 * project the locales it is written in.
 *
 * <p>Every mode asks these questions before it can do anything else, so they sit in the library
 * rather than in any one mode's entry point.
 */
public final class Projects {

    private Projects() {}

    /**
     * Discovers Docusaurus projects under the given directory.
     * A directory is considered a Docusaurus project if it contains both a {@code docs/}
     * subdirectory and a {@code docusaurus.config.js} or {@code docusaurus.config.ts} file.
     *
     * @param worksDir the parent directory to scan
     * @return sorted list of paths to detected Docusaurus project directories
     * @throws IOException if the directory cannot be listed
     */
    public static List<Path> findProjects(Path worksDir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(worksDir)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> Files.isDirectory(p.resolve("docs"))
                            && (Files.exists(p.resolve("docusaurus.config.js"))
                             || Files.exists(p.resolve("docusaurus.config.ts"))))
                  .sorted()
                  .forEach(result::add);
        }
        return result;
    }

    /**
     * Reads i18n configuration from {@code docusaurus.config.ts} or {@code .js}.
     * Returns an array where index 0 is the defaultLocale and subsequent entries are
     * alternate locales. Returns an empty array if no i18n config is found.
     */
    public static String[] readI18nConfig(Path projectDir) {
        Path config = null;
        for (String name : new String[]{"docusaurus.config.ts", "docusaurus.config.js"}) {
            Path p = projectDir.resolve(name);
            if (Files.exists(p)) { config = p; break; }
        }
        if (config == null) return new String[0];
        try {
            String content = Files.readString(config);
            var defM = java.util.regex.Pattern.compile("defaultLocale:\\s*['\"]([^'\"]+)['\"]")
                           .matcher(content);
            if (!defM.find()) return new String[0];
            String defaultLocale = defM.group(1);

            var locM = java.util.regex.Pattern.compile("locales:\\s*\\[([^\\]]+)\\]")
                           .matcher(content);
            List<String> locales = new ArrayList<>();
            locales.add(defaultLocale);
            if (locM.find()) {
                var itemM = java.util.regex.Pattern.compile("['\"]([^'\"]+)['\"]")
                                .matcher(locM.group(1));
                while (itemM.find()) {
                    String loc = itemM.group(1);
                    if (!loc.equals(defaultLocale)) locales.add(loc);
                }
            }
            return locales.toArray(new String[0]);
        } catch (IOException e) {
            return new String[0];
        }
    }

    /** Returns true if {@code dir} exists and contains at least one {@code .md} file. */
    public static boolean hasMarkdownFiles(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".md"));
        } catch (IOException e) {
            return false;
        }
    }
}
