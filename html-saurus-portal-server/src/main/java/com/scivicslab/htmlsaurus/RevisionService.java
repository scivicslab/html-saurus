package com.scivicslab.htmlsaurus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Block;
import org.commonmark.node.Document;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * The work behind the portal's Revise panel ({@code RevisionPanel_261120_oo01}): finding the Markdown lines a
 * selection in the right pane stands for, asking a local LLM for proposals and for the revised text, writing the
 * revised lines back, and keeping one record per revision.
 */
final class RevisionService {

    /** Asks the local LLM; {@link TranslationClient#chatForRevision} in the portal, a fake in tests. */
    interface Llm {
        String chat(String system, String user) throws IOException, InterruptedException;
    }

    /**
     * The Markdown lines a selection stands for.
     *
     * @param path      the file, relative to the works directory
     * @param fromLine  the first line, counted from 1 in the file
     * @param toLine    the last line
     * @param original  the text of those lines
     * @param hash      the SHA-256 of the whole file when it was read
     * @param generated whether a rule file ({@code .lisp}) beside it generates the file, so a write would be lost
     */
    record Located(String path, int fromLine, int toLine, String original, String hash, boolean generated) {}

    /**
     * One place the LLM would change.
     *
     * @param quote   the text it would change, quoted from the original
     * @param problem what is wrong with it
     * @param fix     how to change it
     */
    record Proposal(String quote, String problem, String fix) {}

    /** Default directory of the antipattern collection, under the works directory. */
    static final String DEFAULT_ANTIPATTERN_DIR =
            "doc_Base010/docs/ProjectStandard2/030_ProgrammingProject/200_documentation/100_AntiPattern";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();
    private static final TextContentRenderer TEXT = TextContentRenderer.builder().build();
    /** How many leading characters of a selected block are looked for in the Markdown's blocks. */
    private static final int KEY_LENGTH = 30;

    private final Path worksDir;
    private final Llm llm;
    private final Path logFile;
    private final Path antipatternDir;

    RevisionService(Path worksDir, Llm llm) {
        this(worksDir, llm, worksDir.resolve(".html-saurus").resolve("revisions.jsonl"),
                worksDir.resolve(DEFAULT_ANTIPATTERN_DIR));
    }

    RevisionService(Path worksDir, Llm llm, Path logFile, Path antipatternDir) {
        this.worksDir = worksDir.toAbsolutePath().normalize();
        this.llm = llm;
        this.logFile = logFile;
        this.antipatternDir = antipatternDir;
    }

    /**
     * @param rel a path relative to the works directory
     * @return the file, when it is a {@code .md} file under the works directory; {@code null} otherwise
     */
    Path markdownFile(String rel) {
        if (rel == null || rel.isBlank()) return null;
        Path file = worksDir.resolve(rel).normalize();
        boolean ok = file.startsWith(worksDir) && file.getFileName().toString().endsWith(".md")
                && Files.isRegularFile(file);
        return ok ? file : null;
    }

    /**
     * Finds the lines from the block whose text begins like {@code startText} to the block whose text begins like
     * {@code endText}. Blank space is ignored on both sides, since the right pane's text is laid out by the browser.
     *
     * @return the lines, or {@code null} when either block is not found
     */
    Located locate(String rel, String startText, String endText) throws IOException {
        Path file = markdownFile(rel);
        if (file == null) return null;
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        int bodyStart = frontMatterLines(lines);
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, bodyStart, lines.length));
        List<Candidate> blocks = new ArrayList<>();
        collect(PARSER.parse(body), blocks);
        Candidate start = find(blocks, startText);
        Candidate end = find(blocks, endText == null || endText.isBlank() ? startText : endText);
        if (start == null || end == null) return null;
        int from = Math.min(start.firstLine, end.firstLine);
        int to = Math.max(start.lastLine, end.lastLine);
        int fileFrom = bodyStart + from + 1;
        int fileTo = bodyStart + to + 1;
        String original = String.join("\n", java.util.Arrays.copyOfRange(lines, fileFrom - 1, fileTo));
        boolean generated = Files.isRegularFile(
                file.resolveSibling(file.getFileName().toString().replaceFirst("\\.md$", ".lisp")));
        return new Located(worksDir.relativize(file).toString(), fileFrom, fileTo, original, hash(content), generated);
    }

    /** A block of the parsed Markdown: its text with blank space removed, and its lines in the parsed body. */
    private record Candidate(String text, int firstLine, int lastLine) {}

    private static void collect(Node node, List<Candidate> out) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Block && !(child instanceof Document) && !child.getSourceSpans().isEmpty()) {
                List<SourceSpan> spans = child.getSourceSpans();
                out.add(new Candidate(squeeze(TEXT.render(child)),
                        spans.get(0).getLineIndex(), spans.get(spans.size() - 1).getLineIndex()));
            }
            collect(child, out);
        }
    }

    /** The smallest block whose text contains the selected block's leading characters. */
    private static Candidate find(List<Candidate> blocks, String selected) {
        String s = squeeze(selected == null ? "" : selected);
        if (s.isEmpty()) return null;
        String key = s.substring(0, Math.min(KEY_LENGTH, s.length()));
        return blocks.stream().filter(b -> !b.text.isEmpty() && (b.text.contains(key) || key.contains(b.text) && b.text.length() >= 8))
                .min(Comparator.comparingInt(b -> b.text.length())).orElse(null);
    }

    /** @return {@code text} without blank space of any kind */
    static String squeeze(String text) {
        return text.replaceAll("[\\s\\u3000]+", "");
    }

    /** @return how many lines the front matter at the head takes, its two {@code ---} lines included; 0 when none */
    static int frontMatterLines(String[] lines) {
        if (lines.length == 0 || !lines[0].strip().equals("---")) return 0;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) return i + 1;
        }
        return 0;
    }

    /** @return the SHA-256 of {@code content}, as hexadecimal */
    static String hash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The rule text an instruction stands for.
     *
     * @param kind  {@code propose}, {@code antipattern} or {@code free}
     * @param value the antipattern's document id, or the person's own instruction
     */
    String instruction(String kind, String value) throws IOException {
        return switch (kind == null ? "" : kind) {
            case "propose" -> "Find every place a reader would find hard to read or would misread, and say how to fix it.";
            case "antipattern" -> {
                Path doc = antipatternFile(value);
                if (doc == null) throw new IllegalArgumentException("no antipattern document " + value);
                String text = Files.readString(doc, StandardCharsets.UTF_8);
                String[] lines = text.split("\n", -1);
                yield "Apply only this rule, and report only the places that break it:\n\n"
                        + String.join("\n", java.util.Arrays.copyOfRange(lines, frontMatterLines(lines), lines.length));
            }
            case "free" -> {
                if (value == null || value.isBlank()) throw new IllegalArgumentException("the instruction is empty");
                yield value;
            }
            default -> throw new IllegalArgumentException("unknown instruction kind " + kind);
        };
    }

    /**
     * @return the antipattern collection's documents, each {@code {id, title}}, in file name order
     */
    List<ObjectNode> antipatterns() throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        if (!Files.isDirectory(antipatternDir)) return out;
        try (Stream<Path> files = Files.walk(antipatternDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                String id = null, title = null;
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8).stream().limit(20).toList()) {
                    if (id == null && line.startsWith("id:")) id = line.substring(3).strip();
                    if (title == null && line.startsWith("title:")) title = line.substring(6).strip();
                }
                if (id != null) out.add(JSON.createObjectNode().put("id", id).put("title", title == null ? id : title));
            }
        }
        return out;
    }

    private Path antipatternFile(String id) throws IOException {
        if (id == null || id.isBlank() || !Files.isDirectory(antipatternDir)) return null;
        try (Stream<Path> files = Files.walk(antipatternDir)) {
            return files.filter(p -> p.getFileName().toString().contains(id) && p.toString().endsWith(".md"))
                    .findFirst().orElse(null);
        }
    }

    /**
     * Asks the LLM for proposals on {@code original} under {@code rule}, and keeps those whose quote is in the
     * original: a quote that is not there points at text the LLM made up.
     */
    List<Proposal> proposals(String original, String rule) throws IOException, InterruptedException {
        String system = """
                You review a part of a document. Report each place that should change, under the rule the user gives.
                Answer with JSON only, in this form, and nothing else:
                {"proposals":[{"quote":"...","problem":"...","fix":"..."}]}
                "quote" is copied exactly from the text, character for character, and is as short as still finds the place.
                "problem" says what is wrong; "fix" says how to change it. Write both in the language the text is written in.
                Report nothing the rule does not cover. When nothing should change, answer {"proposals":[]}.""";
        String user = "RULE:\n" + rule + "\n\nTEXT:\n" + original;
        String answer = llm.chat(system, user);
        List<Proposal> out = new ArrayList<>();
        JsonNode list = jsonIn(answer).path("proposals");
        String squeezedOriginal = squeeze(original);
        for (JsonNode p : list) {
            String quote = p.path("quote").asText("");
            if (quote.isBlank() || !squeezedOriginal.contains(squeeze(quote))) continue;
            out.add(new Proposal(quote, p.path("problem").asText(""), p.path("fix").asText("")));
        }
        return out;
    }

    /**
     * The request a chat-ui conversation gets for a harder revision: where the text is, so it can read the whole
     * document and what it refers to, the rule, the text, and the form of the answer.
     */
    static String conversationPrompt(String path, int fromLine, int toLine, String original, String rule) {
        return "Review lines " + fromLine + "-" + toLine + " of the Markdown file " + path
                + " (relative to the works directory). You may read the whole file and the documents it refers to.\n\n"
                + "RULE:\n" + rule + "\n\nTEXT (those lines):\n" + original + "\n\n"
                + "Answer with JSON only, in this form, and nothing else:\n"
                + "{\"proposals\":[{\"quote\":\"...\",\"problem\":\"...\",\"fix\":\"...\"}]}\n"
                + "\"quote\" is copied exactly from TEXT; write \"problem\" and \"fix\" in the language TEXT is written in. "
                + "Do not change the file yourself.";
    }

    /**
     * The proposals in a conversation's reply. A reply that holds no proposals in that form is one proposal whose
     * problem is the whole reply, so the person still sees what the conversation said.
     */
    List<Proposal> proposalsFromReply(String reply) {
        List<Proposal> out = fromJson(jsonIn(reply).path("proposals"));
        if (out.isEmpty()) out.add(new Proposal("", reply == null ? "" : reply.strip(), ""));
        return out;
    }

    /** Asks the LLM for {@code original} with exactly the accepted proposals applied, and nothing else changed. */
    String applyProposals(String original, List<Proposal> accepted) throws IOException, InterruptedException {
        String system = """
                You revise a part of a Markdown document. Apply exactly the changes listed, and change nothing else:
                keep every other character, the Markdown markup, the line breaks and the blank lines as they are.
                Answer with the whole revised text only, with no explanation and no code fence around it.""";
        StringBuilder user = new StringBuilder("CHANGES:\n");
        int n = 1;
        for (Proposal p : accepted) {
            user.append(n++).append(". At \"").append(p.quote()).append("\": ").append(p.problem())
                    .append(" -> ").append(p.fix()).append("\n");
        }
        user.append("\nTEXT:\n").append(original);
        return stripFence(llm.chat(system, user.toString()));
    }

    /**
     * What a write-back did.
     *
     * @param written whether the file was changed
     * @param reason  why it was not, when it was not
     * @param hash    the file's SHA-256 after the write
     */
    record WriteResult(boolean written, String reason, String hash) {}

    /**
     * @return the text of lines {@code fromLine}..{@code toLine}, when the file is still what it was when it was read
     *         ({@code expectedHash}); {@code null} otherwise
     */
    String linesIfUnchanged(String rel, int fromLine, int toLine, String expectedHash) throws IOException {
        Path file = markdownFile(rel);
        if (file == null) return null;
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (!hash(content).equals(expectedHash)) return null;
        String[] lines = content.split("\n", -1);
        if (fromLine < 1 || toLine < fromLine || toLine > lines.length) return null;
        return String.join("\n", java.util.Arrays.copyOfRange(lines, fromLine - 1, toLine));
    }

    /**
     * Replaces lines {@code fromLine}..{@code toLine} with {@code revised}, when the file is still what it was when
     * it was read. A file a rule file ({@code .lisp}) generates is not written: the next generation would undo it.
     */
    WriteResult writeBack(String rel, int fromLine, int toLine, String expectedHash, String revised) throws IOException {
        Path file = markdownFile(rel);
        if (file == null) return new WriteResult(false, "not a Markdown file under the works directory", null);
        Path rule = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.md$", ".lisp"));
        if (Files.isRegularFile(rule)) {
            return new WriteResult(false, "the file is generated from " + rule.getFileName()
                    + "; change the rule file instead, or the next generation undoes this", null);
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (!hash(content).equals(expectedHash)) {
            return new WriteResult(false, "the file changed after the part was taken in; take it in again", null);
        }
        String[] lines = content.split("\n", -1);
        if (fromLine < 1 || toLine < fromLine || toLine > lines.length) {
            return new WriteResult(false, "lines " + fromLine + "-" + toLine + " are not in the file", null);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fromLine - 1; i++) out.append(lines[i]).append('\n');
        out.append(revised.endsWith("\n") ? revised.substring(0, revised.length() - 1) : revised);
        for (int i = toLine; i < lines.length; i++) out.append('\n').append(lines[i]);
        String written = out.toString();
        Files.writeString(file, written, StandardCharsets.UTF_8);
        return new WriteResult(true, null, hash(written));
    }

    /** Adds one revision's record to the record file, with the time it was added. */
    void log(JsonNode record) throws IOException {
        ObjectNode line = JSON.createObjectNode().put("time", OffsetDateTime.now().toString());
        record.fields().forEachRemaining(e -> line.set(e.getKey(), e.getValue()));
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, JSON.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** @return the JSON object in an answer, which may come inside a code fence or after a line of prose */
    static JsonNode jsonIn(String answer) {
        if (answer == null) return JSON.createObjectNode();
        int open = answer.indexOf('{');
        int close = answer.lastIndexOf('}');
        if (open < 0 || close < open) return JSON.createObjectNode();
        try {
            return JSON.readTree(answer.substring(open, close + 1));
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    /** @return {@code text} without a code fence around the whole of it */
    static String stripFence(String text) {
        String t = text == null ? "" : text.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) return t.substring(firstNewline + 1, lastFence).stripTrailing();
        }
        return t;
    }

    /** @return the proposals as a JSON array */
    static ArrayNode toJson(List<Proposal> proposals) {
        ArrayNode a = JSON.createArrayNode();
        for (Proposal p : proposals) a.add(JSON.createObjectNode().put("quote", p.quote()).put("problem", p.problem()).put("fix", p.fix()));
        return a;
    }

    /** @return the proposals a JSON array describes */
    static List<Proposal> fromJson(JsonNode array) {
        List<Proposal> out = new ArrayList<>();
        for (JsonNode p : array) out.add(new Proposal(p.path("quote").asText(""), p.path("problem").asText(""), p.path("fix").asText("")));
        return out;
    }
}
