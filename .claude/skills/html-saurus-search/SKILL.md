---
name: html-saurus-search
description: "Search the local html-saurus documentation portal (all doc_* project docs under ~/works) instead of loading whole files into context. Use when unsure about a self-authored program's spec / API / behavior, or any question answerable from the internal docs. Three similarity search routes (full-text, TF-IDF similar-doc, embedding semantic), deterministic id/directory lookups, a directed prerequisite-reading relation (both directions), and a recursive read-only-what-you-need workflow that leans on the State-Machine doc structure (1 transition = 1 file, self-contained pre/post conditions)."
version: 1.4.0
---

# html-saurus search SKILL

`html-saurus` is a local HTTP service that indexes every `doc_*` documentation portal under
`~/works` (its Document Root is `~/works`). Query it to answer questions about the team's own
programs — specs, APIs, launch conventions, state machines — without pulling entire documents
into context. It returns pointers (`srcPath`) so you then read only the sections you need.

## When to use

- You are unsure how a self-authored program works (its spec, API surface, a class/method,
  a launch/port convention, a state transition) and the answer likely lives in the docs.
- You want to fan out from one relevant doc to its neighbors (recursively) to build a picture.
- You are about to grep the whole tree or read a long doc top-to-bottom — search first, then
  read only the matching section.
- Before deep-reading an unfamiliar doc, check whether it declares required background reading
  (see "Prerequisite documents" below) — read those first, not after you get confused.

Prefer this over loading many files: retrieve pointers, then `Read` just the relevant
`用語定義` (terminology) block plus the one transition/section that matches.

## Endpoint (base URL)

By convention html-saurus runs on the reserved port **`http://localhost:28001`** (see the
AI-workspace port convention: 28001-28009 are reserved fixed ports for search services).

Discover the actual port if 28001 is not listening (it may be an older build, a second
instance in the pool 28010+, or not started):

```bash
# Find a running html-saurus and its port (read raw output; do not filter blindly)
ss -Htlnp | grep -i html-saurus        # or check the AI-workspace dashboard tile
# Probe a candidate port
curl -s "http://localhost:28001/search?q=test" | head -c 200
```

If it is not running, launch it from the AI-workspace dashboard (tool `html-saurus`), or
standalone: `java -jar ~/works/html-saurus.jar ~/works --portal-mode --serve --port 28001`.

## Similarity search routes

The JSON API lives under `/api/*` and returns an array of hits, each:
`{ "id", "title", "path", "srcPath", "summary" }`. The non-`/api` paths — `/search`,
`/search-semantic`, `/related-semantic` — serve **HTML pages for humans**; never call them
programmatically (they return HTML, not JSON).
- `title`  — document title.
- `path`   — the document's path/id in the index; pass it to `/api/related...`.
- `srcPath`— absolute path to the source Markdown on disk; **`Read` this file directly**.
             May be empty on some MoreLikeThis hits — then re-search, or open `path`.
- `summary`— a short snippet for ranking relevance.

| Route | Method + path | Use for |
|---|---|---|
| Full-text (Lucene) | `GET /api/search?q=<query>` | exact identifiers — a class/method/file name, an error string, a config key. May return `[]` when the term is not in the rendered-doc index; then use semantic |
| Semantic (embedding) | `GET /api/search-semantic?q=<query>` — or `POST /api/search-semantic` with the raw text as body | conceptual questions ("how does X decide the port?", "what happens on cutover?") where wording differs from the docs. Most reliable route |
| Similar to a doc (MoreLikeThis) | `GET /api/related?path=<docPath>` | "more docs like this one" — fan out from a hit's `path` |
| Similar to pasted text (MoreLikeThis) | `POST /api/find-related` with the text as body | find docs related to a snippet you already have |
| Related (precomputed, semantic) | `GET /api/related-semantic?path=<docPath>` | semantic neighbors of a doc |

Semantic routes need the embedding index and a reachable embed server; if either is absent they
return an empty array — fall back to full-text. Reindexing (`/api/scan-works-dir`,
`/api/reindex-all`, `/api/build-all/<project>` etc.) is non-production-only and not part of
normal agent use.

## Deterministic lookups (not similarity — exact, no scoring)

Use these when you already know an id, or need a structural relation the similarity routes
cannot express (all return the same `{id,title,path,srcPath,summary}` hit shape):

| Route | Use for |
|---|---|
| `GET /api/resolve?id=<docId>` (alias `?ref=`) | You know the exact document id (or a path fragment) and want its canonical URL/`srcPath` with no search noise. 404 if it doesn't resolve. |
| `GET /api/siblings?id=<docId>` | Table-of-contents proximity — other docs in the same grouping directory (same transition/spec unit). |
| `GET /api/prerequisites?id=<docId>` | **Prerequisite documents** — see below. Directed, not a similarity score. |
| `GET /api/prerequisite-of?id=<docId>` | **Reverse of `prerequisites`** — see below. |

### Prerequisite documents

Distinct from every route above: a document can declare, in a trailing `## 参考文献` section,
which other documents must be understood first. This relation is **directed and can hold between
documents that share almost no vocabulary** (e.g. a physical-cluster doc as prerequisite for an
unrelated-sounding k8s-operations doc) — so full-text/TF-IDF/semantic search will not reliably
surface it (verified empirically; see `PrerequisiteDocument_260728_oo01`, doc_SCIVICS002,
`040_design`). Call `GET /api/prerequisites?id=<docId>` before deep-reading a doc; if it returns
hits, read those first. An empty array does **not** mean "no prerequisites exist" — it means none
has been recorded yet (the section is author-written, not computed). The equivalent MCP tool is
`prerequisites`.

The reverse direction — which documents name *this* one as a prerequisite — is
`GET /api/prerequisite-of?id=<docId>` (MCP tool `prerequisite-of`). It is not author-written: it is
derived by walking every document's `## 参考文献` at index-build time, so it can lag a very recent
edit elsewhere. Use it to move *forward* along the state machine from a doc you already understand
to the docs that build on it — the complement of `prerequisites`, which moves *backward* to
required background. See `PrerequisiteOf_260806_oo01`, doc_SCIVICS002, `040_design`.

An MCP endpoint (JSON-RPC 2.0, `tools/list`/`tools/call`) is also exposed at `/mcp`, mirroring
every REST route above as a tool with a matching name (20 total): `resolve`, `search`,
`find-related`, `related`, `search-semantic`, `related-semantic`, `prerequisites`,
`prerequisite-of`, `siblings`, `list-documents`, `read-document`, `edit-document`, `build-html`,
`build-index`, `build-embedding`, `build-all`, `reindex-all`, `scan-works-dir`, `navbar-labels`,
`translate` (`list-documents`/`read-document`/`edit-document` have no REST equivalent). Full
request/response examples are recorded in `HtmlSaurusMcp_260803_oo01` (MCP) and
`HtmlSaurusApi_260802_oo01` (REST) — both under doc_SCIVICS002, `html-saurus/010_concepts` —
read those directly (`Read`, not this skill) when you need
the exact parameter/response contract rather than just "which route do I call."

### Examples

```bash
BASE=http://localhost:28001

# Conceptual question — semantic
curl -s "$BASE/api/search-semantic?q=how%20does%20AI-workspace%20assign%20ports" \
  | python3 -m json.tool

# Exact identifier — full-text (JSON API; note: /api/search, not /search)
curl -s "$BASE/api/search?q=ProcessSupervisor" | python3 -m json.tool

# Fan out from a hit to its neighbors
curl -s "$BASE/api/related?path=<paste hit.path here>" | python3 -m json.tool

# Related to a snippet you already have
curl -s -X POST "$BASE/api/find-related" \
  --data-binary 'reserved fixed port reuse a running instance' | python3 -m json.tool

# Before deep-reading a doc, check its declared prerequisites
curl -s "$BASE/api/prerequisites?id=<paste hit.id here>" | python3 -m json.tool

# Move forward: which docs build on the one you just read?
curl -s "$BASE/api/prerequisite-of?id=<paste hit.id here>" | python3 -m json.tool
```

## Recommended workflow (read only what you need)

The docs follow the State-Machine Mind Set: one transition per file, each with a self-contained
`用語定義` (terminology) block and explicit pre/post conditions. So you never need the whole
corpus — retrieve, then read the exact node.

1. **Search** the concept (semantic) or the identifier (full-text). Take the top few hits.
2. **Check prerequisites**: `GET /api/prerequisites?id=<hit.id>`. If it returns hits, read those
   first (they can be on a completely different topic — that is the point of the relation).
3. **Read only** the best hit's `srcPath` with the `Read` tool — and within it, the `用語定義`
   block plus the single transition/section that matches. Skip the rest.
4. **If insufficient**, fan out: `GET /api/related?path=<hit.path>` (or `related-semantic`) for
   similar docs, or `GET /api/prerequisite-of?id=<hit.id>` to move forward to docs that build on
   this one. Repeat from step 2 on the new neighbor. Recurse until the question is answered.
5. **Stop early.** Each transition file states its own achievement check; once the matching
   node answers the question, do not keep reading siblings.

## Notes

- Read the raw JSON; do not assume a hit is relevant from `title` alone — check `summary`.
- `srcPath` is the source of truth to read; the rendered `path` is for `/api/related` and the
  portal URL, not for `Read`.
- Search covers every `doc_*` portal under `~/works` at once (cross-project), so scope your
  query with distinctive terms when you only want one project.
