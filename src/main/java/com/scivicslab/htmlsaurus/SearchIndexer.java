package com.scivicslab.htmlsaurus;

import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Builds a Lucene full-text search index from Markdown files in a Docusaurus {@code docs/} directory.
 *
 * <p>Uses a {@link org.apache.lucene.analysis.ja.JapaneseAnalyzer} to support Japanese text search.
 * Each Markdown file is indexed with its title, document ID (from frontmatter), body text,
 * and a summary snippet for display in search results.
 */
public class SearchIndexer {

    private final Path docsDir;
    private final Path indexDir;

    /**
     * @param docsDir source directory containing Markdown files to index
     * @param indexDir target directory for the Lucene index
     */
    public SearchIndexer(Path docsDir, Path indexDir) {
        this.docsDir = docsDir;
        this.indexDir = indexDir;
    }

    /**
     * Rebuilds the search index from scratch. Walks all {@code .md} files in the docs directory
     * and creates a Lucene index with fields: path, title, title_idx, body, summary, doc_id.
     *
     * @throws IOException if file I/O or index writing fails
     */
    public void index() throws IOException {
        var config = new IndexWriterConfig(new JapaneseAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        try (var dir = new NIOFSDirectory(indexDir);
             var writer = new IndexWriter(dir, config)) {

            Files.walkFileTree(docsDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(".md")) {
                        indexFile(writer, file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * Indexes a single Markdown file. Extracts title and doc ID from YAML frontmatter,
     * strips Markdown formatting from the body, and adds the document to the index.
     */
    private void indexFile(IndexWriter writer, Path mdFile) throws IOException {
        String source = Files.readString(mdFile);

        // Extract title and id from frontmatter
        String title = "";
        String docId = "";
        String body = source;
        if (source.startsWith("---")) {
            int end = source.indexOf("\n---", 3);
            if (end != -1) {
                String fm = source.substring(3, end);
                body = source.substring(end + 4).stripLeading();
                for (String line : fm.split("\n")) {
                    if (line.startsWith("title:")) {
                        title = line.substring(6).trim().replaceAll("^\"|\"$", "");
                    } else if (line.startsWith("id:")) {
                        docId = line.substring(3).trim().replaceAll("^\"|\"$", "");
                    }
                }
            }
        }
        // Fall back to leading # heading
        if (body.startsWith("# ")) {
            int nl = body.indexOf('\n');
            String headingTitle = (nl >= 0 ? body.substring(2, nl) : body.substring(2)).trim();
            body = nl >= 0 ? body.substring(nl + 1).stripLeading() : "";
            if (title.isBlank()) title = headingTitle;
        }

        String plainText = stripMarkdown(body);
        String summary = plainText.length() > 250 ? plainText.substring(0, 250) + "…" : plainText;

        Path rel = docsDir.relativize(mdFile);
        String href = "/" + rel.toString().replace('\\', '/').replaceAll("\\.md$", ".html");

        Document doc = new Document();
        doc.add(new StoredField("path", href));
        doc.add(new StoredField("title", title));
        doc.add(new StoredField("summary", summary));
        // title_idx and doc_id are boosted at query time; body is not stored (only indexed)
        doc.add(new TextField("title_idx", title, Field.Store.NO));
        doc.add(new TextField("body", plainText, Field.Store.NO));
        if (!docId.isBlank()) {
            doc.add(new StoredField("doc_id", docId));
            doc.add(new StringField("doc_id_idx", docId, Field.Store.NO));
        }

        writer.addDocument(doc);
    }

    /** Strips Markdown formatting (code blocks, links, emphasis, headings, etc.) to produce plain text for indexing. */
    private String stripMarkdown(String md) {
        String s = md;
        s = s.replaceAll("(?s)```.*?```", " ");                    // fenced code blocks
        s = s.replaceAll("(?s):::\\w+.*?:::", " ");                // admonitions
        s = s.replaceAll("`[^`]+`", " ");                          // inline code
        s = s.replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1");   // links / images
        s = s.replaceAll("[*_]{1,3}([^*_\n]+)[*_]{1,3}", "$1");   // bold / italic
        s = s.replaceAll("(?m)^#{1,6}\\s+", "");                  // headings
        s = s.replaceAll("(?m)^>+\\s*", "");                       // blockquotes
        s = s.replaceAll("(?m)^[|].*[|]\\s*$", " ");              // table rows
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }
}
