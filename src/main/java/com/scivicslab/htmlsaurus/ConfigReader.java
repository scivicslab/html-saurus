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
     * Reads every project-level setting from {@code projectRoot}, in this order:
     *
     * <ol>
     *   <li>site name — {@code title.message} in
     *       {@code i18n/<locale>/docusaurus-theme-classic/navbar.json}, else {@code navbar.title}
     *       in {@code docusaurus.config.ts/js}, else {@code fallbackSiteName};</li>
     *   <li>{@code html-saurus.css} — production mode only;</li>
     *   <li>{@code html-saurus-header.html} — production mode only;</li>
     *   <li>{@code html-saurus-footer.html} — production mode only;</li>
     *   <li>{@code html-saurus-toc-footer.html} — production mode only;</li>
     *   <li>favicon — the file {@code favicon:} names, read from {@code static/};</li>
     *   <li>logo — {@code navbar.logo}'s {@code src} and {@code alt};</li>
     *   <li>site URL — {@code url};</li>
     *   <li>{@code navbar.primaryItems} in {@code html-saurus.properties}.</li>
     * </ol>
     *
     * Items 2 to 5 also honour a locale-specific variant, e.g. {@code html-saurus-footer.en.html}.
     * A project that states none of this still builds: every value has a default or is null.
     */
    static ProjectConfig read(Path projectRoot, boolean production, String currentLocale,
                              String fallbackSiteName) {
        String configSiteName = readSiteNameFromConfig(projectRoot, currentLocale);
        String[] logoInfo = readLogoInfo(projectRoot);
        return new ProjectConfig(
                configSiteName != null ? configSiteName : fallbackSiteName,
                production ? readLocalized(projectRoot, "html-saurus.css", currentLocale) : null,
                production ? readLocalized(projectRoot, "html-saurus-header.html", currentLocale) : null,
                production ? readLocalized(projectRoot, "html-saurus-footer.html", currentLocale) : null,
                production ? readLocalized(projectRoot, "html-saurus-toc-footer.html", currentLocale) : null,
                readFaviconDataUrl(projectRoot),
                logoInfo[0],
                logoInfo[1],
                readSiteUrl(projectRoot),
                readNavPrimaryItems(projectRoot));
    }

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

    /** Default number of navbar sections shown inline when the project does not say. */
    static final int DEFAULT_NAV_PRIMARY_ITEMS = 4;

    /**
     * Reads {@code navbar.primaryItems} from {@code html-saurus.properties} at the project root:
     * how many top-level sections the navbar shows inline before the rest collapse into "More".
     * A value of {@code 0} or less shows every section and renders no "More" button, which is what
     * Docusaurus does. Returns {@link #DEFAULT_NAV_PRIMARY_ITEMS} when the file, the key, or a
     * readable integer is absent.
     */
    static int readNavPrimaryItems(Path projectRoot) {
        Path props = projectRoot.resolve("html-saurus.properties");
        if (!Files.exists(props)) return DEFAULT_NAV_PRIMARY_ITEMS;
        try (var in = Files.newInputStream(props)) {
            var p = new java.util.Properties();
            p.load(in);
            String v = p.getProperty("navbar.primaryItems");
            if (v == null) return DEFAULT_NAV_PRIMARY_ITEMS;
            return Integer.parseInt(v.trim());
        } catch (IOException | NumberFormatException e) {
            System.err.println("Warning: could not read navbar.primaryItems from " + props
                               + ": " + e.getMessage());
            return DEFAULT_NAV_PRIMARY_ITEMS;
        }
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
