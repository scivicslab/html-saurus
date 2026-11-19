package com.scivicslab.htmlsaurus;

import java.util.ArrayList;
import java.util.List;

/**
 * Which documents a PDF export holds and in what order: the part of the sidebar tree under one directory of a doc
 * project, flattened in the order the sidebar shows it (ExportToPdf_261119_oo01).
 *
 * <p>Each directory under the chosen one becomes a heading, one level deeper per directory, and each document comes
 * after the heading of the directory it is in, one level deeper than that heading.</p>
 *
 * @param title the PDF's title: the chosen directory's label
 * @param parts the headings and documents, in sidebar order
 */
record PdfExportPlan(String title, List<Part> parts) {

    /**
     * One heading or document of the PDF.
     *
     * @param level  1 for directly under the chosen directory, one more per directory below it
     * @param title  the heading's text, or the document's title
     * @param href   the page this part's body comes from ({@code /path/}), or {@code null} for a directory with no page
     * @param folder true for a directory's heading, false for a document
     * @param number the part's number, from 1, which names its anchor in the PDF
     */
    record Part(int level, String title, String href, boolean folder, int number) {
        /** @return the id of this part's place in the combined HTML */
        String anchor() {
            return ANCHOR_PREFIX + number;
        }

    }

    /** The start of every part's anchor: {@code hs-part-<number>}. */
    static final String ANCHOR_PREFIX = "hs-part-";

    /** @return the number of the part an anchor names, or {@code null} when the name is not a part's anchor */
    static Integer partNumberOfAnchor(String name) {
        if (name == null || !name.startsWith(ANCHOR_PREFIX)) return null;
        try {
            return Integer.valueOf(name.substring(ANCHOR_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @return how many parts are documents (a directory with a page of its own counts as one) */
    int documentCount() {
        return (int) parts.stream().filter(p -> p.href() != null).count();
    }

    /**
     * Plans the export of one directory.
     *
     * @param root       the sidebar tree of the doc project ({@code NavTreeBuilder#build})
     * @param dirRel     the directory, relative to {@code docs/}; blank for the whole project
     * @param fallbackTitle the title when the chosen directory has no node of its own (the whole project)
     * @return the plan; its parts are empty when no document is under the directory
     */
    static PdfExportPlan planOfDirectory(SiteNode root, String dirRel, String fallbackTitle) {
        String rel = dirRel == null ? "" : dirRel.replace('\\', '/').replaceAll("^/+|/+$", "");
        String prefix = rel.isEmpty() ? "/" : "/" + PageRenderer.cleanRelPath(rel) + "/";
        SiteNode directory = rel.isEmpty() ? null : directoryNodeOf(root, rel.split("/").length, prefix);
        List<SiteNode> top = directory != null ? List.of(directory) : maximal(root, prefix);
        List<Part> parts = new ArrayList<>();
        int[] next = {1};
        String title;
        if (top.size() == 1 && top.get(0).isDir() && !rel.isEmpty()) {
            SiteNode chosen = top.get(0);
            title = chosen.label();
            // A directory with a same-name page of its own: that page opens the PDF.
            if (chosen.catLink() != null) parts.add(new Part(1, chosen.label(), chosen.catLink(), false, next[0]++));
            for (SiteNode child : chosen.children()) add(child, 1, parts, next);
        } else {
            title = rel.isEmpty() ? fallbackTitle : rel.substring(rel.lastIndexOf('/') + 1);
            for (SiteNode node : top) {
                if (node == root) {
                    for (SiteNode child : root.children()) add(child, 1, parts, next);
                } else {
                    add(node, 1, parts, next);
                }
            }
        }
        return new PdfExportPlan(title, List.copyOf(parts));
    }

    private static void add(SiteNode node, int level, List<Part> parts, int[] next) {
        if (node.isDir()) {
            if (leaves(node).isEmpty()) return;
            // A directory that is one document's own folder (its page, and files such as scripts) is a document.
            if (node.catLink() != null && node.children().stream().allMatch(child -> leaves(child).isEmpty())) {
                parts.add(new Part(level, node.label(), node.catLink(), false, next[0]++));
                return;
            }
            parts.add(new Part(level, node.label(), node.catLink(), true, next[0]++));
            for (SiteNode child : node.children()) add(child, level + 1, parts, next);
        } else if (node.href() != null) {
            parts.add(new Part(level, node.label(), node.href(), false, next[0]++));
        }
    }

    /**
     * The node of the directory itself: from the root, one directory level per path segment, each time the one child
     * all of whose pages are under {@code prefix}. A project whose only content is the chosen directory has the same
     * pages at the root, so the highest node covering them is not the directory.
     *
     * @return the node, or {@code null} when the tree does not follow the directories that far (a hand-written sidebar)
     */
    private static SiteNode directoryNodeOf(SiteNode root, int segments, String prefix) {
        SiteNode node = root;
        for (int i = 0; i < segments; i++) {
            SiteNode found = null;
            for (SiteNode child : node.children()) {
                List<String> pages = leaves(child);
                if (child.isDir() && !pages.isEmpty() && pages.stream().allMatch(h -> h.startsWith(prefix))) {
                    if (found != null) return null;
                    found = child;
                }
            }
            if (found == null) return null;
            node = found;
        }
        return node;
    }

    /** The highest nodes all of whose pages are under {@code prefix}, in tree order. */
    private static List<SiteNode> maximal(SiteNode node, String prefix) {
        List<String> leaves = leaves(node);
        if (!leaves.isEmpty() && leaves.stream().allMatch(h -> h.startsWith(prefix))) return List.of(node);
        List<SiteNode> out = new ArrayList<>();
        if (node.isDir()) for (SiteNode child : node.children()) out.addAll(maximal(child, prefix));
        return out;
    }

    /** Every page under a node: its own page, then its children's. */
    private static List<String> leaves(SiteNode node) {
        List<String> out = new ArrayList<>();
        if (!node.isDir()) {
            if (node.href() != null) out.add(node.href());
            return out;
        }
        if (node.catLink() != null) out.add(node.catLink());
        for (SiteNode child : node.children()) out.addAll(leaves(child));
        return out;
    }
}
