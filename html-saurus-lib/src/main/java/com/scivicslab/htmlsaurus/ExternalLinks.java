package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Collects the external addresses a built site links to, and the pages that link to each. */
public final class ExternalLinks {

    private ExternalLinks() {}

    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);

    /**
     * @param siteDir the directory the build wrote
     * @return each {@code http(s)} address in an anchor, without its fragment, with the URLs of the
     *         pages that carry it; in the order they are first met
     * @throws IOException if the directory cannot be read
     */
    public static Map<String, List<String>> collect(Path siteDir) throws IOException {
        return collect(siteDir, null);
    }

    /**
     * The host of the site's own address, from {@code url:} in the Docusaurus config, lower-cased;
     * {@code null} when the config names none.
     */
    public static String ownHostOf(Path projectRoot) {
        String url = ConfigReader.readSiteUrl(projectRoot);
        if (url == null) return null;
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Like {@link #collect(Path)}, leaving out the addresses on {@code ownHost}: a link to the site
     * itself is an internal link, and {@link LinkChecker} looks it up in the built pages.
     *
     * @param ownHost the site's own host, or {@code null} to leave nothing out
     */
    public static Map<String, List<String>> collect(Path siteDir, String ownHost) throws IOException {
        Map<String, List<String>> found = new LinkedHashMap<>();
        List<Path> pages;
        try (Stream<Path> walk = Files.walk(siteDir)) {
            pages = walk.filter(p -> p.getFileName().toString().endsWith(".html")).sorted().toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        for (Path page : pages) {
            String rel = "/" + siteDir.relativize(page).toString().replace('\\', '/');
            String url = rel.endsWith("/index.html") ? rel.substring(0, rel.length() - "index.html".length()) : rel;
            Matcher m = ANCHOR.matcher(Files.readString(page, StandardCharsets.UTF_8));
            while (m.find()) {
                String href = LinkChecker.unescape(m.group(1) != null ? m.group(1) : m.group(2)).strip();
                if (!(href.startsWith("http://") || href.startsWith("https://"))) continue;
                if (ownHost != null && ExternalLinkChecker.hostOf(href).equals(ownHost)) continue;
                int hash = href.indexOf('#');
                if (hash >= 0) href = href.substring(0, hash);
                List<String> pagesOfUrl = found.computeIfAbsent(href, k -> new ArrayList<>());
                if (!pagesOfUrl.contains(url)) pagesOfUrl.add(url);
            }
        }
        return found;
    }
}
