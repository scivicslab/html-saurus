package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads Docusaurus project configuration files (docusaurus.config.ts/js, i18n JSON, etc.)
 * and returns derived values used by SiteBuilder during initialization.
 */
class ConfigReader {

    private ConfigReader() {}

    /**
     * Reads the site name to display in the navbar.
     * Priority: (1) {@code "title"} in {@code i18n/<locale>/docusaurus-theme-classic/navbar.json},
     * (2) {@code navbar.title} in {@code docusaurus.config.ts/js}, (3) returns {@code null}.
     */
    static String readSiteNameFromConfig(Path projectRoot, String locale) {
        // 1. i18n/<locale>/docusaurus-theme-classic/navbar.json
        if (locale != null) {
            Path navbarJson = projectRoot.resolve(
                "i18n/" + locale + "/docusaurus-theme-classic/navbar.json");
            if (Files.exists(navbarJson)) {
                try {
                    String content = Files.readString(navbarJson);
                    var m = java.util.regex.Pattern
                        .compile("\"title\":\\s*\\{\\s*\"message\":\\s*\"([^\"]+)\"")
                        .matcher(content);
                    if (m.find()) return m.group(1);
                } catch (IOException ignored) {}
            }
        }
        // 2. docusaurus.config.ts/js: navbar: { title: '...' }
        for (String name : new String[]{"docusaurus.config.ts", "docusaurus.config.js"}) {
            Path cfg = projectRoot.resolve(name);
            if (Files.exists(cfg)) {
                try {
                    String content = Files.readString(cfg);
                    var m = java.util.regex.Pattern
                        .compile("navbar:\\s*\\{[^}]*?title:\\s*['\"]([^'\"]+)['\"]",
                                 java.util.regex.Pattern.DOTALL)
                        .matcher(content);
                    if (m.find()) return m.group(1);
                } catch (IOException ignored) {}
            }
        }
        return null;
    }

    /**
     * Reads the favicon from the project's {@code static/} directory and returns it as a data URL.
     * The favicon path is read from {@code favicon:} in {@code docusaurus.config.ts/js}.
     * Returns {@code null} if not found.
     */
    static String readFaviconDataUrl(Path projectRoot) {
        for (String name : new String[]{"docusaurus.config.ts", "docusaurus.config.js"}) {
            Path cfg = projectRoot.resolve(name);
            if (!Files.exists(cfg)) continue;
            try {
                String content = Files.readString(cfg);
                var m = java.util.regex.Pattern
                    .compile("favicon:\\s*['\"]([^'\"]+)['\"]")
                    .matcher(content);
                if (!m.find()) continue;
                Path faviconFile = projectRoot.resolve("static").resolve(m.group(1));
                if (!Files.exists(faviconFile)) continue;
                byte[] bytes = Files.readAllBytes(faviconFile);
                String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                String ext = faviconFile.getFileName().toString().toLowerCase();
                String mime = ext.endsWith(".svg") ? "image/svg+xml"
                            : ext.endsWith(".png") ? "image/png"
                            : ext.endsWith(".jpg") || ext.endsWith(".jpeg") ? "image/jpeg"
                            : "image/x-icon";
                return "data:" + mime + ";base64," + base64;
            } catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * Reads the navbar logo from the project's {@code static/} directory and returns
     * {@code [dataUrl, alt]}. Returns {@code [null, ""]} if not configured or file is absent.
     * The logo path and alt text are read from {@code themeConfig.navbar.logo} in
     * {@code docusaurus.config.ts/js}.
     */
    static String[] readLogoInfo(Path projectRoot) {
        for (String name : new String[]{"docusaurus.config.ts", "docusaurus.config.js"}) {
            Path cfg = projectRoot.resolve(name);
            if (!Files.exists(cfg)) continue;
            try {
                String content = Files.readString(cfg);
                var logoBlock = java.util.regex.Pattern
                    .compile("logo:\\s*\\{([^}]+)\\}", java.util.regex.Pattern.DOTALL)
                    .matcher(content);
                if (!logoBlock.find()) continue;
                String block = logoBlock.group(1);
                var srcM = java.util.regex.Pattern.compile("src:\\s*['\"]([^'\"]+)['\"]").matcher(block);
                if (!srcM.find()) continue;
                String src = srcM.group(1);
                var altM = java.util.regex.Pattern.compile("alt:\\s*['\"]([^'\"]+)['\"]").matcher(block);
                String alt = altM.find() ? altM.group(1) : "";
                Path imgFile = projectRoot.resolve("static").resolve(src);
                if (!Files.exists(imgFile)) continue;
                byte[] bytes = Files.readAllBytes(imgFile);
                String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                String ext = imgFile.getFileName().toString().toLowerCase();
                String mime = ext.endsWith(".svg") ? "image/svg+xml"
                            : ext.endsWith(".png") ? "image/png"
                            : ext.endsWith(".jpg") || ext.endsWith(".jpeg") ? "image/jpeg"
                            : "image/png";
                return new String[]{"data:" + mime + ";base64," + base64, alt};
            } catch (IOException ignored) {}
        }
        return new String[]{null, ""};
    }

    /**
     * Reads the site URL from {@code url:} in {@code docusaurus.config.ts/js}.
     * Returns {@code null} if not found. Trailing slashes are stripped.
     */
    static String readSiteUrl(Path projectRoot) {
        for (String name : new String[]{"docusaurus.config.ts", "docusaurus.config.js"}) {
            Path cfg = projectRoot.resolve(name);
            if (!Files.exists(cfg)) continue;
            try {
                String content = Files.readString(cfg);
                var m = java.util.regex.Pattern.compile("url:\\s*['\"]([^'\"]+)['\"]").matcher(content);
                if (m.find()) return m.group(1).replaceAll("/+$", "");
            } catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * Reads a localized customization file, trying locale-specific name first.
     * For {@code html-saurus-header.html} with locale {@code en}, tries
     * {@code html-saurus-header.en.html} then falls back to {@code html-saurus-header.html}.
     * Returns {@code null} if neither file exists.
     */
    static String readLocalized(Path dir, String filename, String locale) {
        if (locale != null) {
            int dot = filename.lastIndexOf('.');
            String localized = dot >= 0
                ? filename.substring(0, dot) + "." + locale + filename.substring(dot)
                : filename + "." + locale;
            String content = readOptional(dir.resolve(localized));
            if (content != null) return content;
        }
        return readOptional(dir.resolve(filename));
    }

    /** Reads a file to a String, or returns null if the file does not exist. */
    static String readOptional(Path p) {
        if (!Files.exists(p)) return null;
        try { return Files.readString(p); }
        catch (IOException e) { System.err.println("Warning: could not read " + p + ": " + e.getMessage()); return null; }
    }

    /**
     * Returns true if {@code docusaurus.config.ts/js} contains a navbar item with {@code to: '/blog'}.
     * Used to decide whether to render the Blog navbar section rather than auto-adding it.
     */
    static boolean hasBlogNavbarEntry(Path projectRoot) {
        for (String name : new String[]{"docusaurus.config.ts", "docusaurus.config.js"}) {
            Path cfg = projectRoot.resolve(name);
            if (!Files.exists(cfg)) continue;
            try {
                String content = Files.readString(cfg);
                // Match: to: '/blog' or to: "/blog"
                if (java.util.regex.Pattern
                        .compile("to:\\s*['\"]/?blog/?['\"]")
                        .matcher(content).find()) return true;
            } catch (IOException ignored) {}
        }
        return false;
    }

    /**
     * Walks up from {@code docsDir} to find the Docusaurus project root — the nearest ancestor
     * that contains a {@code docs/} subdirectory. This correctly handles alternate-locale builds
     * where {@code docsDir} is deep inside {@code i18n/<locale>/docusaurus-plugin-content-docs/current}.
     */
    static Path findProjectRoot(Path docsDir) {
        Path p = docsDir.getParent();
        while (p != null) {
            if (Files.isDirectory(p.resolve("docs"))) return p;
            p = p.getParent();
        }
        return docsDir.getParent(); // fallback: should not happen in valid Docusaurus layout
    }
}
