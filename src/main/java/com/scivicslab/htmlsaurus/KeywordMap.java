package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic "keyword &rarr; axis &rarr; document" mapping, authored by hand and used to surface
 * high-precision document pointers ABOVE the semantic/full-text search results.
 *
 * <p>The table is a line-oriented text file (one rule per line), so it needs no JSON/YAML library and
 * can be edited either in a text editor or through the portal's editor page:
 * <pre>
 *   subject &gt; axis &gt; term1, term2, ... : ref1, ref2, ...
 *   # lines starting with '#' are comments; blank lines are ignored
 * </pre>
 * where {@code subject} is the topic (e.g. {@code POJO-actor}), {@code axis} is the angle
 * (e.g. {@code install}/{@code usage}/{@code internals}, free text), {@code term*} are the trigger
 * words that must appear in the prompt, and {@code ref*} are document references (a document id or a
 * fragment of its path) resolved to real hits by a {@link Resolver}.
 *
 * <p>Matching is AND substring (case-insensitive): a rule fires only when every trigger term appears
 * in the prompt. This keeps results precise and few.
 */
public final class KeywordMap {

    /** One rule: all {@code terms} must appear in the prompt for the rule to fire. */
    public record Rule(String subject, String axis, List<String> terms, List<String> docRefs) {}

    /**
     * Resolves a document reference (a document id or a path fragment) to an enriched hit map with
     * keys {@code id,title,path,srcPath,summary}; returns {@code null} when it cannot be resolved.
     */
    @FunctionalInterface
    public interface Resolver {
        Map<String, String> resolve(String ref);
    }

    private final List<Rule> rules;

    public KeywordMap(List<Rule> rules) {
        this.rules = rules;
    }

    public int size() {
        return rules.size();
    }

    public List<Rule> rules() {
        return rules;
    }

    /** Parses the line-oriented table file; a missing file yields an empty map (no rules). */
    public static KeywordMap load(Path file) {
        List<Rule> rules = new ArrayList<>();
        if (file == null || !Files.exists(file)) {
            return new KeywordMap(rules);
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Rule r = parseLine(raw);
                if (r != null) {
                    rules.add(r);
                }
            }
        } catch (IOException e) {
            System.err.println("KeywordMap: could not read " + file + ": " + e.getMessage());
        }
        return new KeywordMap(rules);
    }

    /**
     * Parses one line {@code "subject > axis > term1, term2 : ref1, ref2"}.
     * Returns {@code null} for blank lines, comments, or lines with no document reference.
     */
    static Rule parseLine(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        int colon = line.lastIndexOf(':');
        if (colon < 0) {
            return null;
        }
        List<String> docRefs = splitList(line.substring(colon + 1), ",");
        if (docRefs.isEmpty()) {
            return null;
        }
        String left = line.substring(0, colon).strip();
        String[] seg = left.split(">", 3);
        String subject;
        String axis;
        String termsSeg;
        if (seg.length >= 3) {
            subject = seg[0].strip();
            axis = seg[1].strip();
            termsSeg = seg[2];
        } else if (seg.length == 2) {
            subject = seg[0].strip();
            axis = "";
            termsSeg = seg[1];
        } else {
            subject = "";
            axis = "";
            termsSeg = left;
        }
        List<String> terms = splitList(termsSeg, ",");
        if (terms.isEmpty()) {
            return null;
        }
        return new Rule(subject, axis, terms, docRefs);
    }

    /** Splits on the separator, trimming each element and dropping empties. */
    private static List<String> splitList(String s, String sep) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (String part : s.split(java.util.regex.Pattern.quote(sep))) {
            String t = part.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Returns the curated hits for a prompt: for every rule whose trigger terms all appear in the
     * prompt, each referenced document is resolved and returned as a hit map with keys
     * {@code id,title,path,srcPath,summary,axis,subject}. The summary is prefixed with
     * {@code [curated: subject / axis]} so the agent sees the curation context. Hits are de-duplicated
     * by source path / served path / reference.
     */
    public List<Map<String, String>> lookup(String prompt, Resolver resolver) {
        List<Map<String, String>> out = new ArrayList<>();
        if (prompt == null || prompt.isBlank()) {
            return out;
        }
        String hay = prompt.toLowerCase(Locale.ROOT);
        Set<String> seen = new LinkedHashSet<>();
        for (Rule r : rules) {
            if (r.terms().isEmpty() || !allTermsPresent(hay, r.terms())) {
                continue;
            }
            for (String ref : r.docRefs()) {
                Map<String, String> resolved = resolver == null ? null : resolver.resolve(ref);
                Map<String, String> m = new LinkedHashMap<>();
                if (resolved != null) {
                    m.putAll(resolved);
                }
                m.putIfAbsent("id", ref);
                m.putIfAbsent("title", ref);
                m.putIfAbsent("path", "");
                m.putIfAbsent("srcPath", "");
                String base = m.getOrDefault("summary", "");
                String tag = "[curated: " + r.subject() + (r.axis().isBlank() ? "" : " / " + r.axis()) + "] ";
                m.put("summary", tag + (base == null ? "" : base));
                m.put("axis", r.axis());
                m.put("subject", r.subject());
                String key = !m.get("srcPath").isBlank() ? m.get("srcPath")
                           : !m.get("path").isBlank() ? m.get("path") : ref;
                if (seen.add(key)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static boolean allTermsPresent(String lowerPrompt, List<String> terms) {
        for (String t : terms) {
            String term = t.toLowerCase(Locale.ROOT).strip();
            if (term.isEmpty()) {
                continue;
            }
            if (!lowerPrompt.contains(term)) {
                return false;
            }
        }
        return true;
    }
}
