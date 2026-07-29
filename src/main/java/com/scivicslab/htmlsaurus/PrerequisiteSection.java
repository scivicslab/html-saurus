package com.scivicslab.htmlsaurus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code ### 前提文書} section that an author may place at the end of a document's
 * Markdown source, per {@code PrerequisiteDocument_260728_oo01} (doc_SCIVICS002/html-saurus/040_design).
 *
 * <p>The section is a plain bullet list of backtick-quoted document id references, one per line,
 * with an optional free-text reason after the reference:
 * <pre>
 *   ### 前提文書
 *
 *   - `POJOActorConcept_251023_oo01` — Turing-workflow is built on POJO-actor's actor model
 * </pre>
 */
final class PrerequisiteSection {

    private static final String HEADING = "### 前提文書";
    private static final Pattern HEADING_LEVEL = Pattern.compile("^(#{1,3})\\s");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+.*");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    private PrerequisiteSection() {}

    /**
     * Returns the document id references listed under the {@code ### 前提文書} heading, in the
     * order they appear. Returns an empty list when the heading is absent or has no resolvable
     * bullet items. The scan stops at the next heading of level 1-3, or at end of file.
     */
    static List<String> extractRefs(String markdownSource) {
        List<String> refs = new ArrayList<>();
        if (markdownSource == null || markdownSource.isBlank()) {
            return refs;
        }
        String[] lines = markdownSource.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].strip().equals(HEADING)) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            return refs;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (HEADING_LEVEL.matcher(line).find()) {
                break;
            }
            if (!BULLET.matcher(line).matches()) {
                continue;
            }
            Matcher m = BACKTICKED.matcher(line);
            if (m.find()) {
                String ref = m.group(1).strip();
                if (!ref.isEmpty() && seen.add(ref)) {
                    refs.add(ref);
                }
            }
        }
        return refs;
    }
}
