package com.scivicslab.htmlsaurus;

import com.scivicslab.openmathlisp.markdown.DocumentIdentifier;
import com.scivicslab.openmathlisp.markdown.MarkdownDocument;
import com.scivicslab.openmathlisp.write.TermWriter;
import com.scivicslab.openmathlisp.symbols.SymbolTable;
import com.scivicslab.openmathlisp.term.TermFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The two directions between the formula source a document stores and the LaTeX a reader sees.
 *
 * <p>A document stores each display formula as a fenced code block with the info string {@code om} and each
 * inline formula as a code span starting with {@code om:}; both hold an s-expression whose head symbols come
 * from the OpenMath content dictionaries (see {@code FormulaSourceInMarkdown_261002_oo01}, doc_Base010
 * {@code openmath-lisp/010_concepts}).</p>
 *
 * <p>{@link #toLatex} is the display direction: the browser cannot draw an s-expression, so the text handed
 * to the markdown converter and to the search index has the om pieces replaced by {@code $$ ... $$} and
 * {@code $ ... $}, which KaTeX draws as before. The replacement happens in memory; the file on disk keeps
 * the s-expressions, so the formula source is never overwritten by its own rendering.</p>
 *
 * <p>{@link #toOmBlocks} is the import direction: OCR returns formulas as LaTeX, so a freshly imported
 * document is written with its readable formulas already turned into om blocks. What the reader cannot read
 * without a declaration stays LaTeX with a comment giving the reason, and becomes an om block when the
 * declaration is written and the document is converted again.</p>
 */
final class FormulaSource {

    private static final Logger LOG = Logger.getLogger(FormulaSource.class.getName());

    private static SymbolTable symbols;
    private static TermFactory factory;
    private static TermWriter writer;

    private FormulaSource() {
    }

    /** Loads the bundled symbol table once; later calls reuse it. */
    private static synchronized void load() {
        if (symbols == null) {
            symbols = SymbolTable.loadBundled();
            factory = new TermFactory(symbols);
            writer = new TermWriter(symbols);
        }
    }

    /**
     * Tells whether a markdown text holds any formula source. Texts without it are returned untouched by
     * {@link #toLatex(String, Path)}, so this check keeps the common page off the s-expression reader.
     *
     * @param markdown the text of a markdown file
     * @return true when the text holds an om block or an om span
     */
    static boolean hasFormulaSource(String markdown) {
        return markdown.contains("```om") || markdown.contains("`om:");
    }

    /**
     * Replaces the formula source of a markdown text with LaTeX.
     *
     * <p>Failure is reported and the text is returned unchanged: a page showing s-expressions is better
     * than a build that stops.</p>
     *
     * @param markdown the text of a markdown file
     * @param source   the file the text came from, for the log message
     * @return the text with {@code $$ ... $$} and {@code $ ... $} in place of the om pieces
     */
    static String toLatex(String markdown, Path source) {
        if (!hasFormulaSource(markdown)) {
            return markdown;
        }
        try {
            load();
            return MarkdownDocument.parse(markdown, factory).render(writer);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Formula source in " + source + " could not be written as LaTeX: "
                    + e.getMessage() + "; leaving the page as it is.", e);
            return markdown;
        }
    }

    /**
     * Reads a markdown file and returns its text with the formula source written as LaTeX.
     *
     * @param markdown the file
     * @return the text for the markdown converter
     * @throws IOException when the file cannot be read
     */
    static String readAsLatex(Path markdown) throws IOException {
        return toLatex(Files.readString(markdown, StandardCharsets.UTF_8), markdown);
    }

    /**
     * Turns the LaTeX formulas of a freshly imported markdown text into om blocks and om spans.
     *
     * <p>Failure is reported and the text is returned unchanged: an imported document with LaTeX formulas
     * is better than a lost import.</p>
     *
     * @param markdown the text the import assembled
     * @param target   the file the text will be written to; its path gives the formula identifiers
     * @return the text with om blocks and om spans in place of the LaTeX that could be read
     */
    static String toOmBlocks(String markdown, Path target) {
        try {
            load();
            MarkdownDocument.ConversionResult result = MarkdownDocument.parse(markdown, factory)
                    .convert(DocumentIdentifier.prefixFor(target), factory);
            LOG.info("Formula source in " + target.getFileName() + ": " + result.blocks() + " om blocks, "
                    + result.spans() + " om spans, " + result.unreadable() + " left as LaTeX.");
            return result.document().write();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Formulas in " + target + " could not be written as om blocks: "
                    + e.getMessage() + "; leaving them as LaTeX.", e);
            return markdown;
        }
    }
}
