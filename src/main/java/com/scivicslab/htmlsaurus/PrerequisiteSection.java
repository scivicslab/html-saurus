package com.scivicslab.htmlsaurus;

import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses the {@code ## 参考文献} section that an author may place at the end of a document's
 * Markdown source, per {@code PrerequisiteDocument_260728_oo01} (doc_SCIVICS002/html-saurus/040_design).
 *
 * <p>The section body is a single well-formed HTML block, not hand-scanned Markdown bullets: a
 * {@code <ul>} whose {@code <li>} items each contain one {@code <span data-doc-id="...">...}
 * tag, with an optional {@code data-category} attribute for grouping references by kind (e.g. a
 * direct-topic reference vs. a supporting-technique reference). {@code <span>} (not {@code <a>})
 * is used deliberately: the tag exists only to be reliably parseable well-formed markup, not to
 * create a hyperlink, and an unstyled {@code <a>} with no {@code href} rendered as dead-looking
 * link text. The document id is included in the visible text so it survives the build, not only
 * in the {@code data-doc-id} attribute (attributes are invisible in the rendered page).
 * <pre>
 *   ## 参考文献
 *
 *   &lt;ul&gt;
 *   &lt;li&gt;&lt;span data-doc-id="POJOActorConcept_251023_oo01" data-category="direct"&gt;&lt;code&gt;POJOActorConcept_251023_oo01&lt;/code&gt; — Turing-workflow is built on POJO-actor's actor model&lt;/span&gt;&lt;/li&gt;
 *   &lt;/ul&gt;
 * </pre>
 *
 * <p>Section boundaries are found using the CommonMark AST — the same parser
 * {@link MarkdownConverter} already uses — instead of hand-rolled line scanning: fenced code
 * blocks showing a {@code ## 参考文献} example are never mistaken for a real section, because
 * CommonMark itself already excludes fence contents from the document structure. The block's own
 * markup is then parsed as XML via {@link DocumentBuilder}, so a malformed tag fails loudly
 * ({@link MalformedPrerequisitesException}) instead of being silently dropped.
 */
final class PrerequisiteSection {

    private static final String HEADING_TEXT = "参考文献";

    private PrerequisiteSection() {}

    /**
     * One {@code ## 参考文献} entry: the referenced document id, the kind of relation its author
     * declared, and the optional category that predates the kind.
     *
     * @param docId    the referenced document's id, from {@code data-doc-id}
     * @param relation the kind of relation, from {@code data-relation}; required
     * @param category the older, optional grouping from {@code data-category}
     */
    record Ref(String docId, String relation, String category) {}

    /**
     * Thrown when the {@code ## 参考文献} HTML block is present but cannot be read as declared:
     * either it is not well-formed XML, or an entry is missing a required attribute.
     */
    static final class MalformedPrerequisitesException extends RuntimeException {
        MalformedPrerequisitesException(String message, Throwable cause) {
            super(message, cause);
        }

        MalformedPrerequisitesException(String message) {
            super(message, null);
        }
    }

    /**
     * The vocabulary of relation kinds is not defined in advance
     * ({@code RelationKind_260830_oo01}): a kind is whatever word its author needed. Only the shape
     * of the word is checked, so that one relation does not end up split across spellings that
     * differ by case or spacing.
     */
    private static final java.util.regex.Pattern RELATION_FORM =
            java.util.regex.Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /**
     * Returns the document references listed under the {@code ## 参考文献} heading, in the order
     * they appear, deduplicated by {@code data-doc-id}. Returns an empty list when the heading is
     * absent, or is not immediately followed by an HTML block.
     *
     * @throws MalformedPrerequisitesException if the HTML block is present but not well-formed XML
     */
    static List<Ref> extractRefs(String markdownSource) {
        List<Ref> refs = new ArrayList<>();
        if (markdownSource == null || markdownSource.isBlank()) {
            return refs;
        }
        Node document = Parser.builder().build().parse(markdownSource);
        HtmlBlock block = findPrerequisitesBlock(document);
        if (block == null) {
            return refs;
        }
        Document dom = parseXml(block.getLiteral());
        NodeList spans = dom.getElementsByTagName("span");
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < spans.getLength(); i++) {
            Element span = (Element) spans.item(i);
            String docId = span.getAttribute("data-doc-id").strip();
            if (docId.isEmpty() || !seen.add(docId)) {
                continue;
            }
            String relation = span.getAttribute("data-relation").strip();
            if (relation.isEmpty()) {
                throw new MalformedPrerequisitesException(
                        "## 参考文献 entry for " + docId + " has no data-relation attribute."
                        + " Every entry states the kind of relation its author declared.");
            }
            if (!RELATION_FORM.matcher(relation).matches()) {
                throw new MalformedPrerequisitesException(
                        "## 参考文献 entry for " + docId + " has data-relation=\"" + relation
                        + "\", which is not an identifier of lower-case letters, digits and hyphens.");
            }
            String category = span.getAttribute("data-category").strip();
            refs.add(new Ref(docId, relation, category));
        }
        return refs;
    }

    /**
     * Returns a copy of {@code hit} with a {@code "category"} entry set from {@code ref}
     * (empty string when the author wrote no {@code data-category}). {@code hit} is never
     * mutated — it is a defensive copy — so a resolver's possibly cached/shared map instance
     * is left untouched for other callers.
     *
     * <p>Shared by {@code PortalServer.prerequisitesFor} (the {@code GET /api/prerequisites}
     * endpoint) and {@code McpHandler.toolPrerequisites} (the {@code prerequisites} MCP tool,
     * a thin wrapper around the same REST behavior), so the two never drift apart.
     */
    static Map<String, String> withCategory(Map<String, String> hit, Ref ref) {
        Map<String, String> out = new LinkedHashMap<>(hit);
        out.put("relation", ref.relation());
        out.put("category", ref.category());
        return out;
    }

    /** Walks the top-level AST nodes for the {@code ## 参考文献} heading's immediate next sibling. */
    private static HtmlBlock findPrerequisitesBlock(Node document) {
        Node node = document.getFirstChild();
        while (node != null) {
            if (node instanceof Heading heading && heading.getLevel() == 2
                    && headingText(heading).equals(HEADING_TEXT)) {
                Node next = heading.getNext();
                return next instanceof HtmlBlock htmlBlock ? htmlBlock : null;
            }
            node = node.getNext();
        }
        return null;
    }

    private static String headingText(Heading heading) {
        StringBuilder sb = new StringBuilder();
        Node child = heading.getFirstChild();
        while (child != null) {
            if (child instanceof Text text) {
                sb.append(text.getLiteral());
            }
            child = child.getNext();
        }
        return sb.toString();
    }

    private static Document parseXml(String html) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(html)));
        } catch (Exception e) {
            throw new MalformedPrerequisitesException(
                    "## 参考文献 section is not well-formed XML: " + e.getMessage(), e);
        }
    }
}
