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
