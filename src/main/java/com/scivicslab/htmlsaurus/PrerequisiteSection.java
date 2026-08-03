package com.scivicslab.htmlsaurus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code ## 前提文書} section that an author may place at the end of a document's
 * Markdown source, per {@code PrerequisiteDocument_260728_oo01} (doc_SCIVICS002/html-saurus/040_design).
 *
 * <p>The section is a plain bullet list of backtick-quoted document id references, one per line,
 * with an optional free-text reason after the reference:
 * <pre>
 *   ## 前提文書
 *
 *   - `POJOActorConcept_251023_oo01` — Turing-workflow is built on POJO-actor's actor model
 * </pre>
 */
final class PrerequisiteSection {

    private static final String HEADING = "## 前提文書";
    private static final Pattern HEADING_LEVEL = Pattern.compile("^(#{1,6})\\s");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+.*");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
    private static final Pattern FENCE = Pattern.compile("^\\s*```");

    private PrerequisiteSection() {}

    /**
     * Returns the document id references listed under the {@code ## 前提文書} heading, in the
     * order they appear. Returns an empty list when the heading is absent or has no resolvable
     * bullet items. The scan stops at the next heading (any level), or at end of file.
     *
     * <p>Lines inside fenced code blocks ({@code ```...```}) are ignored when looking for the
     * heading and its bullets, so a document that shows a {@code ## 前提文書} example as
     * illustration (as this feature's own reference documentation does) does not have that
     * example mistaken for its own real prerequisites section.
     */
    static List<String> extractRefs(String markdownSource) {
        List<String> refs = new ArrayList<>();
        if (markdownSource == null || markdownSource.isBlank()) {
            return refs;
        }
        String[] lines = markdownSource.split("\n", -1);
        int start = -1;
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            if (FENCE.matcher(lines[i]).find()) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;
            if (lines[i].strip().equals(HEADING)) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            return refs;
        }
        Set<String> seen = new LinkedHashSet<>();
        inFence = false;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (FENCE.matcher(line).find()) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;
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
