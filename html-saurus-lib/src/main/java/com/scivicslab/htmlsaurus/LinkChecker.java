package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the same-site references in a built site that name nothing the build wrote.
 *
 * <p>The check reads only the output directory. A reference is resolved the way a browser resolves
 * it, against the page's own {@code /}-terminated URL, and the result is looked up in the list of
 * files the build produced, so no HTTP request is made and the answer does not depend on a server.
 *
 * <p>A reference that matches only after both sides are lower-cased is reported on its own as
 * {@link Kind#CASE_MISMATCH}. Such a reference works wherever the file system ignores letter case
 * and answers 404 on a Linux server, which is why an author who checks it locally does not see it.
 */
public final class LinkChecker {

    private LinkChecker() {}

    /** Why a reference was reported. */
    public enum Kind {
        /** No file or page directory has this path. */
        MISSING,
        /** A file or page directory has this path in a different letter case. */
        CASE_MISMATCH,
        /** The page exists but carries no {@code id} or {@code name} equal to the fragment. */
        MISSING_FRAGMENT,
        /** A {@code ## 参考資料} entry names a document id that no {@code doc_*} repository declares. */
        UNKNOWN_DOC_ID,
        /**
         * A page under a locale directory such as {@code /en/} names a path that exists only in the
         * default locale. The page was translated and the document it points at was not.
         */
        UNTRANSLATED
    }

    /**
     * One broken reference.
     *
     * @param page the URL of the page holding the reference, for example {@code /blog/post/}
     * @param href the reference exactly as the page spells it, after HTML entities are decoded
     * @param kind why it was reported
     */
    public record Finding(String page, String href, Kind kind) {}

    private static final Pattern TAG = Pattern.compile(
            "<(a|img|link|script|source|iframe)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "\\b(href|src)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANCHOR = Pattern.compile(
            "\\b(?:id|name)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    /** A reference entry, as {@link ReferenceLabels} reads it. */
    private static final Pattern DOC_REFERENCE = Pattern.compile("<span\\s+data-doc-id=\"([^\"]+)\"");
    /** The locale directory a page sits under, such as {@code /en/}. */
    private static final Pattern LOCALE_PREFIX = Pattern.compile("^/[a-z]{2}(?:-[A-Za-z]{2,4})?/");
    private static final Pattern ENTITY = Pattern.compile("&(?:#(\\d+)|#[xX]([0-9a-fA-F]+)|([A-Za-z]+));");
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");

    /**
     * Checks every {@code .html} page under {@code siteDir}.
     *
     * @param siteDir the directory the build wrote, for example {@code static-html/}
     * @return the broken references, ordered by page and then by position in the page
     * @throws IOException if the directory cannot be read
     */
    public static List<Finding> checkInternal(Path siteDir) throws IOException {
        return checkInternal(siteDir, null);
    }

    /**
     * Checks every {@code .html} page under {@code siteDir}, and the document ids its reference
     * entries name.
     *
     * <p>A reference entry that names an id no {@code doc_*} repository beside {@code projectRoot}
     * declares is reported as {@link Kind#UNKNOWN_DOC_ID}. {@link ReferenceLabels} leaves such an
     * entry as the author wrote it, so nothing else shows that it points nowhere.</p>
     *
     * @param siteDir     the directory the build wrote
     * @param projectRoot the repository being built, or {@code null} to skip the id check
     * @return the broken references, ordered by page and then by position in the page
     * @throws IOException if the directory cannot be read
     */
    public static List<Finding> checkInternal(Path siteDir, Path projectRoot) throws IOException {
        ReferenceLabels refs = projectRoot == null ? null : ReferenceLabels.forProject(projectRoot);
        Set<String> files = new HashSet<>();
        try (Stream<Path> walk = Files.walk(siteDir)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> files.add("/" + siteDir.relativize(p).toString().replace('\\', '/')));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        Map<String, String> byLowerCase = new HashMap<>();
        for (String f : files) byLowerCase.putIfAbsent(f.toLowerCase(Locale.ROOT), f);

        Map<String, Set<String>> anchors = new HashMap<>();
        List<String> pages = files.stream().filter(f -> f.endsWith(".html")).sorted().toList();
        List<Finding> findings = new ArrayList<>();
        for (String pageFile : pages) {
            String pageUrl = urlOf(pageFile);
            String html = Files.readString(siteDir.resolve(pageFile.substring(1)), StandardCharsets.UTF_8);
            Set<String> seen = new LinkedHashSet<>();
            if (refs != null && !refs.isEmpty()) {
                Matcher ref = DOC_REFERENCE.matcher(html);
                while (ref.find()) {
                    String id = unescape(ref.group(1));
                    if (!id.isBlank() && !refs.knows(id) && seen.add("doc-id:" + id)) {
                        findings.add(new Finding(pageUrl, id, Kind.UNKNOWN_DOC_ID));
                    }
                }
            }
            Matcher tag = TAG.matcher(html);
            while (tag.find()) {
                String wanted = switch (tag.group(1).toLowerCase(Locale.ROOT)) {
                    case "a", "link" -> "href";
                    default -> "src";
                };
                Matcher attr = ATTRIBUTE.matcher(tag.group(2));
                while (attr.find()) {
                    if (!attr.group(1).equalsIgnoreCase(wanted)) continue;
                    String raw = unescape(attr.group(2) != null ? attr.group(2) : attr.group(3));
                    if (raw.isBlank() || !seen.add(raw)) continue;
                    Kind kind = judge(raw, pageUrl, pageFile, siteDir, files, byLowerCase, anchors);
                    if (kind != null) findings.add(new Finding(pageUrl, raw, kind));
                }
            }
        }
        return findings;
    }

    /** The URL a built file is served at: a page directory's index is served at the directory. */
    private static String urlOf(String file) {
        return file.endsWith("/index.html") ? file.substring(0, file.length() - "index.html".length()) : file;
    }

    /** Returns why the reference is broken, or {@code null} when it resolves. */
    private static Kind judge(String raw, String pageUrl, String pageFile, Path siteDir,
                              Set<String> files, Map<String, String> byLowerCase,
                              Map<String, Set<String>> anchors) {
        String ref = raw.strip();
        if (ref.startsWith("//") || SCHEME.matcher(ref).find()) return null;

        String fragment = null;
        int hash = ref.indexOf('#');
        if (hash >= 0) {
            fragment = decode(ref.substring(hash + 1));
            ref = ref.substring(0, hash);
        }
        int query = ref.indexOf('?');
        if (query >= 0) ref = ref.substring(0, query);

        String targetFile;
        if (ref.isEmpty()) {
            targetFile = pageFile;
        } else {
            String path = decode(ref);
            if (path == null) return Kind.MISSING;
            String absolute = path.startsWith("/") ? path : pageUrl.substring(0, pageUrl.lastIndexOf('/') + 1) + path;
            String normal = normalize(absolute);
            if (normal == null) return Kind.MISSING;
            targetFile = locate(normal, files);
            if (targetFile == null) {
                String lower = locate(normal.toLowerCase(Locale.ROOT), byLowerCase.keySet());
                if (lower != null) return Kind.CASE_MISMATCH;
                Matcher locale = LOCALE_PREFIX.matcher(pageUrl);
                if (locale.find() && normal.startsWith(locale.group(0))
                        && locate(normal.substring(locale.group(0).length() - 1), files) != null) {
                    return Kind.UNTRANSLATED;
                }
                return Kind.MISSING;
            }
        }
        if (fragment == null || fragment.isEmpty() || !targetFile.endsWith(".html")) return null;
        Set<String> ids = anchors.computeIfAbsent(targetFile, f -> anchorsOf(siteDir.resolve(f.substring(1))));
        return ids.contains(fragment) ? null : Kind.MISSING_FRAGMENT;
    }

    /** The built file a normalized absolute path names, or {@code null} when none does. */
    private static String locate(String path, Set<String> files) {
        if (path.endsWith("/")) {
            String index = path + "index.html";
            return files.contains(index) ? index : null;
        }
        if (files.contains(path)) return path;
        String index = path + "/index.html";
        return files.contains(index) ? index : null;
    }

    /** Folds {@code .} and {@code ..}; {@code null} when the path climbs above the site root. */
    private static String normalize(String absolute) {
        Deque<String> parts = new ArrayDeque<>();
        String[] segments = absolute.split("/", -1);
        for (String s : segments) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.removeLast();
                continue;
            }
            parts.addLast(s);
        }
        String joined = "/" + String.join("/", parts);
        boolean directory = absolute.endsWith("/") || absolute.endsWith("/.") || absolute.endsWith("/..");
        return directory && !joined.endsWith("/") ? joined + "/" : joined;
    }

    private static Set<String> anchorsOf(Path file) {
        Set<String> ids = new HashSet<>();
        try {
            Matcher m = ANCHOR.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) ids.add(unescape(m.group(1) != null ? m.group(1) : m.group(2)));
        } catch (IOException e) {
            // An unreadable target has no anchors to offer, so a fragment aimed at it is reported.
        }
        return ids;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Decodes the character references an HTML attribute value can carry. */
    private static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(0);
            try {
                if (m.group(1) != null) {
                    replacement = new String(Character.toChars(Integer.parseInt(m.group(1))));
                } else if (m.group(2) != null) {
                    replacement = new String(Character.toChars(Integer.parseInt(m.group(2), 16)));
                } else {
                    replacement = switch (m.group(3)) {
                        case "amp" -> "&";
                        case "lt" -> "<";
                        case "gt" -> ">";
                        case "quot" -> "\"";
                        case "apos" -> "'";
                        case "nbsp" -> "\u00A0";
                        case "ndash" -> "\u2013";
                        case "mdash" -> "\u2014";
                        default -> m.group(0);
                    };
                }
            } catch (IllegalArgumentException e) {
                // An out-of-range reference is left as written.
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
