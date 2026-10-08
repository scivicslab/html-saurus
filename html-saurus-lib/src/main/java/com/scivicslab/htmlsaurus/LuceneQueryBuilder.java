package com.scivicslab.htmlsaurus;

import java.util.Map;

/**
 * Builds Lucene query expressions of the form:
 * {@code field1:value^boost OR field2:value^boost ...}
 *
 * <p>Each field uses its own analyzer (via PerFieldAnalyzerWrapper) when parsed by QueryParser,
 * so no manual tokenization or escaping is applied here.
 */
class LuceneQueryBuilder {

    /**
     * The fields a search reads, in the order they are tried.
     *
     * <p>Returned as a fresh array each call because the callers pass it straight into Lucene,
     * which takes a {@code String[]} and could write into a shared one.
     *
     * @return the field names
     */
    static String[] fields() {
        return FIELDS.clone();
    }

    private static final String[] FIELDS =
        {"title_idx", "doc_id_idx", "path_tokens", "tags", "body"};

    /**
     * How heavily a match in each field counts, against a body match counting one.
     *
     * <p>A title match counts ten, which makes the title the heaviest field. It counted three
     * before, which put it below the document identifier and the path, both of which count five.
     * Measured on a two-page index: searching for {@code widget} put a page in a directory named
     * {@code 020_widget} above a page titled {@code Widget} — 2.67 against 1.09. The order turns
     * over between six and eight; at ten the titled page scores 3.65.
     *
     * <p>Repeating a term in the body does not compete with a title match at either weight. Forty
     * body mentions score 0.17 and four hundred score 0.18, because BM25 saturates term frequency.
     *
     * <p>These weights were written out at four call sites, which meant the same search could rank
     * differently depending on whether it came from the portal, the search server or the MCP
     * handler. They are stated once here.
     */
    static final Map<String, Float> BOOSTS = Map.of(
        "title_idx", 10.0f,
        "doc_id_idx", 5.0f,
        "path_tokens", 5.0f,
        "tags", 2.0f,
        "body", 1.0f);

    /**
     * Builds an OR query expression across all given fields with optional per-field boosts.
     *
     * @param fields   field names to search
     * @param boosts   per-field boost factors (fields absent from the map default to 1.0)
     * @param queryStr the raw user query — passed through unchanged so the analyzer handles it
     * @return a Lucene query string ready to pass to QueryParser
     */
    static String build(String[] fields, Map<String, Float> boosts, String queryStr) {
        var sb = new StringBuilder();
        for (String field : fields) {
            if (sb.length() > 0) sb.append(" OR ");
            sb.append(field).append(":(").append(queryStr).append(")");
            Float boost = boosts != null ? boosts.get(field) : null;
            if (boost != null && boost != 1.0f) sb.append("^").append(boost);
        }
        return sb.toString();
    }
}
