package com.scivicslab.htmlsaurus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the document ids a Markdown source names.
 *
 * <p>An author refers to another document by writing its id in the text; no link is written, so
 * nothing in the built pages shows a name that points nowhere. The id has one shape, a CamelCase
 * content name, a six-digit date and an author code, such as {@code AgentBrief_260905_oo01}.</p>
 *
 * <p>Two parts of a source are not read. The front matter declares the document's own id, which
 * exists by being declared. Fenced code blocks hold examples and commands, where an id is written
 * as a sample and not as a reference.</p>
 */
final class DocIdScanner {

    private DocIdScanner() {}

    /**
     * A name that starts with a letter and not after another name character, so the {@code 010_}
     * in front of a file name is not part of an id, and the same shape inside a longer word is not
     * taken either.
     */
    private static final Pattern ID = Pattern.compile(
            "(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)*_\\d{6}_[a-z]{2}\\d{2}(?![A-Za-z0-9_])");

    /** One id found in a source, with the 1-based line it stands on. */
    record Mention(String id, int line) {}

    /** Every id the Markdown names, in the order it names them. */
    static List<Mention> scan(String markdown) {
        List<Mention> found = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        int i = 0;
        // Front matter only when the very first line opens it: a rule further down is a rule.
        if (lines.length > 0 && lines[0].strip().equals("---")) {
            int close = -1;
            for (int j = 1; j < lines.length; j++) {
                if (lines[j].strip().equals("---")) { close = j; break; }
            }
            if (close > 0) i = close + 1;
        }
        String fence = null;
        for (; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.stripLeading();
            if (fence == null) {
                if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                    fence = stripped.substring(0, 3);
                    continue;
                }
            } else {
                if (stripped.startsWith(fence)) fence = null;
                continue;
            }
            Matcher m = ID.matcher(line);
            while (m.find()) found.add(new Mention(m.group(), i + 1));
        }
        return found;
    }
}
