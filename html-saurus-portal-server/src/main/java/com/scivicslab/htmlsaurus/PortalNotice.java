package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A short message the process that starts the portal leaves for the reader, shown at the top of the portal page: for
 * example that the folders the reader asked for held no project. It is a plain text file named by the environment
 * variable {@value #ENV}, read each time the page is built, so the starter can write it before or after the portal is up.
 *
 * <p>Each line is a paragraph. Text between backticks is shown as code. Everything else is escaped, so the file cannot add
 * markup to the page.
 */
final class PortalNotice {

    /** Name of the environment variable that names the notice file. */
    static final String ENV = "HTML_SAURUS_NOTICE_FILE";

    /** The file is read up to this many bytes; a notice is a few lines. */
    static final int MAX_BYTES = 4000;

    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    private PortalNotice() {}

    /** The file named by {@value #ENV}, or null when it is not set. */
    static Path fromEnvironment() {
        String p = System.getenv(ENV);
        return p == null || p.isBlank() ? null : Path.of(p);
    }

    /** The notice as HTML for the top of the page; empty when there is no file, it is blank or it cannot be read. */
    static String html(Path file) {
        if (file == null) return "";
        String text;
        try {
            if (!Files.isRegularFile(file)) return "";
            byte[] bytes = Files.readAllBytes(file);
            text = new String(bytes, 0, Math.min(bytes.length, MAX_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
        return render(text);
    }

    /** {@code text} as HTML; empty for blank text. */
    static String render(String text) {
        var sb = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            sb.append("<p>").append(inline(line.strip())).append("</p>\n");
        }
        return sb.length() == 0 ? "" : "<div id=\"portal-notice\" class=\"portal-notice\" role=\"status\">\n" + sb + "</div>\n";
    }

    private static String inline(String line) {
        String escaped = HttpUtils.escapeHtml(line);
        Matcher m = CODE.matcher(escaped);
        return m.replaceAll(r -> "<code>" + Matcher.quoteReplacement(r.group(1)) + "</code>");
    }
}
