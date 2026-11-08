package com.scivicslab.htmlsaurus;

import java.util.regex.Pattern;

/**
 * The path under which a server is reached in the browser, when a proxy publishes it below a prefix
 * (for example {@code /pups-with-audit/session/abc123}) instead of at the host root.
 *
 * <p>The proxy removes the prefix before it forwards a request, so the routes of the server are the same
 * with or without a base path. What differs is every URL the server writes into a page or a redirect: the browser
 * resolves a URL that begins with "/" against the host root, which is outside the prefix. Each such URL is
 * written through {@link #url(String)}. With no base path set, {@code url} returns its argument unchanged,
 * so a server that owns the host root behaves as it always did.
 *
 * <p>Set with the environment variable {@value #ENV}. A value that is not a plain path is refused, because it
 * ends up inside markup and scripts.
 */
public final class BasePath {

    /** Name of the environment variable that carries the base path. */
    public static final String ENV = "HTML_SAURUS_BASE_PATH";

    private static final Pattern SAFE = Pattern.compile("/[A-Za-z0-9._~\\-]+(/[A-Za-z0-9._~\\-]+)*");

    private static final BasePath NONE = new BasePath("");

    private static final BasePath FROM_ENVIRONMENT = of(System.getenv(ENV));

    private final String prefix;

    private BasePath(String prefix) {
        this.prefix = prefix;
    }

    /** The base path of this process, read once from {@value #ENV}. */
    public static BasePath current() {
        return FROM_ENVIRONMENT;
    }

    /** No base path: the server is reached at the host root. */
    public static BasePath none() {
        return NONE;
    }

    /**
     * Normalizes {@code raw}: null, blank and "/" mean no base path; otherwise one leading "/" is ensured and
     * trailing "/" are dropped.
     *
     * @throws IllegalArgumentException if what is left is not made of path segments of letters, digits and
     *                                  {@code . _ ~ -}
     */
    public static BasePath of(String raw) {
        if (raw == null) return NONE;
        String p = raw.trim();
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return NONE;
        if (!p.startsWith("/")) p = "/" + p;
        if (!SAFE.matcher(p).matches()) {
            throw new IllegalArgumentException(ENV + " is not a plain path: " + raw);
        }
        return new BasePath(p);
    }

    /** The prefix with a leading "/" and no trailing "/"; empty when there is no base path. */
    public String prefix() {
        return prefix;
    }

    /** {@code rootRelative} (it begins with "/") below the base path. */
    public String url(String rootRelative) {
        if (!rootRelative.startsWith("/")) {
            throw new IllegalArgumentException("not a root-relative URL: " + rootRelative);
        }
        return prefix + rootRelative;
    }

    /** {@code path} with the base path taken off its front; unchanged if it does not begin with it. */
    public String strip(String path) {
        if (prefix.isEmpty()) return path;
        if (path.equals(prefix)) return "/";
        return path.startsWith(prefix + "/") ? path.substring(prefix.length()) : path;
    }
}
