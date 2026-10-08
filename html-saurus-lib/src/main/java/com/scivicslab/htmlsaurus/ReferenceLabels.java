package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes the visible name of a referenced document from its {@code data-doc-id}.
 *
 * <p>A {@code ## 参考資料} entry names the document it points at in an attribute:</p>
 *
 * <pre>{@code
 * <li><span data-doc-id="AgentBrief_260905_oo01" data-relation="prerequisite">— トップページ</span></li>
 * }</pre>
 *
 * <p>A browser draws no attribute, so the author used to write the name a second time in the text
 * as well. Two copies of one fact disagree eventually, and there are hundreds of entries; this
 * writes the visible one at build time instead. The form is
 * {@code <repository>: <directory under docs/>: <id>}, which is what tells a person which document
 * an id names ({@code AgentBrief_260905_oo01}, "遷移の種類").</p>
 *
 * <p>References cross repositories, so the id is looked up across every {@code doc_*} beside the
 * one being built. An id this cannot place is left exactly as the author wrote it: a wrong
 * location is worse than none.</p>
 */
final class ReferenceLabels {

    /** {@code <span data-doc-id="…" …>} through to its {@code </span>}. */
    private static final Pattern ENTRY =
            Pattern.compile("<span\\s+data-doc-id=\"([^\"]+)\"([^>]*)>(.*?)</span>", Pattern.DOTALL);

    /** The first {@code <code>} of an entry, whatever attributes it carries. */
    private static final Pattern CODE = Pattern.compile("<code([^>]*)>([^<]*)</code>");

    /** How much of a file to read looking for the frontmatter {@code id:}. */
    private static final int FRONT_MATTER_BYTES = 600;

    private static final Pattern ID_LINE =
            Pattern.compile("^id:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    /** Document id to the label shown for it. */
    private final Map<String, String> labels;

    private ReferenceLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    /**
     * Reads the frontmatter id of every document in the doc repositories beside {@code projectRoot}.
     *
     * <p>Measured at 0.33 s for 5,431 files, which is why this runs per build rather than being
     * cached: a document added since the last build is then already placed.</p>
     *
     * @param projectRoot the repository being built, e.g. {@code ~/works/doc_Base010}
     * @return a resolver over every {@code doc_*} repository in that directory's parent
     */
    static ReferenceLabels forProject(Path projectRoot) {
        Map<String, String> found = new HashMap<>();
        // Normalised first: built as "." from inside the repository, the parent of the unnormalised
        // path is the repository itself, and no doc_* sits under it.
        Path works = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize().getParent();
        if (works == null || !Files.isDirectory(works)) return new ReferenceLabels(found);
        try (Stream<Path> repos = Files.list(works)) {
            repos.filter(Files::isDirectory)
                 .filter(p -> p.getFileName().toString().startsWith("doc_"))
                 .forEach(repo -> collect(repo, found));
        } catch (IOException e) {
            // A resolver that knows nothing leaves every entry as the author wrote it.
        }
        return new ReferenceLabels(found);
    }

    /** Adds every id under {@code repo/docs} to {@code found}. */
    private static void collect(Path repo, Map<String, String> found) {
        Path docs = repo.resolve("docs");
        if (!Files.isDirectory(docs)) return;
        try (Stream<Path> files = Files.walk(docs)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md"))
                 .filter(p -> {
                     String s = p.toString();
                     return !s.contains("/static-html/") && !s.contains("/build/")
                             && !s.contains("/node_modules/");
                 })
                 .forEach(p -> {
                     String id = frontMatterId(p);
                     if (id == null) return;
                     Path rel = docs.relativize(p);
                     String dir = rel.getNameCount() > 1 ? rel.getName(0).toString() : "docs";
                     found.putIfAbsent(id, repo.getFileName() + ": " + dir + ": " + id);
                 });
        } catch (IOException e) {
            // Skip a repository that cannot be walked; the rest still resolve.
        }
    }

    /** The frontmatter {@code id:} of one file, or null when it declares none. */
    private static String frontMatterId(Path file) {
        try (var in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(FRONT_MATTER_BYTES);
            Matcher m = ID_LINE.matcher(new String(head, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Gives every reference entry in {@code html} the visible name of the document it points at.
     *
     * <p>An entry that already shows a {@code <code>} naming the id has that element's text
     * replaced, so a page rebuilt after the id moved to another directory shows the new place.
     * An entry with no such element gets one in front of whatever the author wrote.</p>
     *
     * @param html the converted page body
     * @return the same HTML with the reference entries labelled
     */
    String apply(String html) {
        if (html == null || labels.isEmpty() || !html.contains("data-doc-id=")) return html;
        Matcher m = ENTRY.matcher(html);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String id = m.group(1);
            String label = labels.get(id);
            String body = m.group(3);
            if (label != null) {
                Matcher code = CODE.matcher(body);
                String named = null;
                while (code.find()) {
                    if (code.group(2).contains(id)) {
                        named = body.substring(0, code.start())
                                + "<code" + code.group(1) + ">" + label + "</code>"
                                + body.substring(code.end());
                        break;
                    }
                }
                body = named != null ? named : "<code>" + label + "</code> " + body;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(
                    "<span data-doc-id=\"" + id + "\"" + m.group(2) + ">" + body + "</span>"));
        }
        m.appendTail(out);
        return out.toString();
    }
}
