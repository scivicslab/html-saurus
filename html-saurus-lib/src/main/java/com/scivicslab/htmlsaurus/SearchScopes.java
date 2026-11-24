package com.scivicslab.htmlsaurus;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The search scopes a reader can tick to narrow a search to part of the documents.
 *
 * <p>A scope is a name and a path pattern written as a Lucene query over {@code path_idx}, the served
 * path of a page (project name, then the page's path), e.g. {@code path_idx:\/doc_Base010\/documentation\/*}.
 * Scopes may overlap: a page in two ticked scopes is found once. One more scope, {@link #REST_ID}, is not
 * stored: it selects every page that no stored scope selects, so the scopes together cover all pages.
 *
 * <p>The scopes live in one JSON file on the server, {@code {"categories":[{"id","name","query"}]}}.
 * This class is immutable; {@link #add} and {@link #remove} return the changed copy.
 */
final class SearchScopes {

    /** The id of the scope that holds the pages no stored scope selects. Not stored in the file. */
    static final String REST_ID = "rest";

    /** The name the screen shows for {@link #REST_ID}. */
    static final String REST_NAME = "Everything else";

    private static final int MAX_NAME = 80;

    /** One stored scope. */
    record Category(String id, String name, String query) {}

    private final List<Category> categories;

    private SearchScopes(List<Category> categories) {
        this.categories = List.copyOf(categories);
    }

    static SearchScopes empty() {
        return new SearchScopes(List.of());
    }

    List<Category> categories() {
        return categories;
    }

    /**
     * The analyzer a scope query is parsed with: {@code path_idx} is one term (the whole path), so the
     * query text on it must not be split into words.
     */
    static Analyzer analyzer(Analyzer base) {
        return new PerFieldAnalyzerWrapper(base, Map.of("path_idx", new KeywordAnalyzer()));
    }

    // ---- change -------------------------------------------------------------------------------

    /**
     * Adds a scope.
     *
     * @param base the analyzer of the index language, used to check that the query can be parsed
     * @throws IllegalArgumentException if the name or the query is blank, or the name is too long
     * @throws ParseException           if the query is not valid Lucene query syntax
     */
    SearchScopes add(String name, String query, Analyzer base) throws ParseException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("The scope needs a name.");
        if (name.strip().length() > MAX_NAME) throw new IllegalArgumentException("The name is longer than " + MAX_NAME + " characters.");
        if (query == null || query.isBlank()) throw new IllegalArgumentException("The scope needs a query.");
        parse(query.strip(), base);
        List<Category> next = new ArrayList<>(categories);
        next.add(new Category(nextId(), name.strip(), query.strip()));
        return new SearchScopes(next);
    }

    /** Removes the scope with this id; the same scopes when there is none. */
    SearchScopes remove(String id) {
        List<Category> next = new ArrayList<>(categories);
        next.removeIf(c -> c.id().equals(id));
        return new SearchScopes(next);
    }

    private String nextId() {
        int max = 0;
        for (Category c : categories) {
            if (c.id().startsWith("c")) {
                try { max = Math.max(max, Integer.parseInt(c.id().substring(1))); }
                catch (NumberFormatException ignored) { /* an id written by hand */ }
            }
        }
        return "c" + (max + 1);
    }

    // ---- select -------------------------------------------------------------------------------

    /**
     * The query that selects the pages of the ticked scopes.
     *
     * @param ids  the ids of the ticked scopes, {@link #REST_ID} included when ticked; ids that name no
     *             scope are ignored
     * @param base the analyzer of the index language
     * @return the filter, or {@code null} when nothing is ticked: no filter, every page
     * @throws ParseException if a stored query cannot be parsed
     */
    Query filter(Collection<String> ids, Analyzer base) throws ParseException {
        if (ids == null || ids.isEmpty()) return null;
        var any = new BooleanQuery.Builder();
        int ticked = 0;
        for (Category c : categories) {
            if (ids.contains(c.id())) {
                any.add(parse(c.query(), base), BooleanClause.Occur.SHOULD);
                ticked++;
            }
        }
        if (ids.contains(REST_ID)) {
            any.add(rest(base), BooleanClause.Occur.SHOULD);
            ticked++;
        }
        return ticked == 0 ? null : any.build();
    }

    /** Every page, except the pages of the stored scopes. Every page when there are no stored scopes. */
    private Query rest(Analyzer base) throws ParseException {
        var rest = new BooleanQuery.Builder().add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
        for (Category c : categories) {
            rest.add(parse(c.query(), base), BooleanClause.Occur.MUST_NOT);
        }
        return rest.build();
    }

    private static Query parse(String query, Analyzer base) throws ParseException {
        var parser = new QueryParser("body", analyzer(base));
        parser.setDefaultOperator(QueryParser.Operator.AND);
        return parser.parse(query);
    }

    // ---- file ---------------------------------------------------------------------------------

    /**
     * Reads the scopes file.
     *
     * @return no scopes when the file does not exist
     * @throws IOException if the file exists but cannot be read as scopes; it is not replaced with an empty
     *                     list, so a damaged file is not lost by the next save
     */
    static SearchScopes load(Path file) throws IOException {
        if (!Files.exists(file)) return empty();
        try {
            return fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("Cannot read the search scopes in " + file + ": " + e.getMessage(), e);
        }
    }

    /** Writes the scopes file whole, or not at all: the new content replaces the old file in one move. */
    void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @SuppressWarnings("unchecked")
    static SearchScopes fromJson(String json) {
        Map<String, Object> root = McpJsonParser.parseObject(json);
        Object list = root.get("categories");
        if (!(list instanceof List<?> items)) throw new IllegalArgumentException("\"categories\" is missing");
        List<Category> out = new ArrayList<>();
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("a scope is not an object");
            out.add(new Category(text(m, "id"), text(m, "name"), text(m, "query")));
        }
        return new SearchScopes(out);
    }

    private static String text(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s) || s.isBlank()) throw new IllegalArgumentException("a scope has no \"" + key + "\"");
        return s;
    }

    /** The scopes as the API and the file carry them: {@code {"categories":[...],"rest":{"id","name"}}}. */
    String toJson() {
        var sb = new StringBuilder("{\"categories\":[");
        for (int i = 0; i < categories.size(); i++) {
            Category c = categories.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(str(c.id()))
              .append(",\"name\":").append(str(c.name()))
              .append(",\"query\":").append(str(c.query())).append('}');
        }
        return sb.append("],\"rest\":{\"id\":").append(str(REST_ID))
                 .append(",\"name\":").append(str(REST_NAME)).append("}}").toString();
    }

    private static String str(String s) {
        var sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
