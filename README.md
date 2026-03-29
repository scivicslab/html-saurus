[![Sponsor](https://img.shields.io/github/sponsors/scivicslab)](https://github.com/sponsors/scivicslab)

# html-saurus

**Official Website: [scivicslab.com](https://scivicslab.com)**

**html-saurus** serves your [Docusaurus](https://docusaurus.io/) `docs/` folder as static HTML — with no Node.js, no npm, no build step.

If you already have a Docusaurus project and want to browse it in environments where Node.js is unavailable, restricted, or simply overkill, html-saurus reads the same Markdown files and directory conventions and serves them directly.

## Why html-saurus?

Docusaurus is great for public documentation sites, but running it requires Node.js and a full npm install. In many environments — air-gapped servers, corporate intranets, HPC clusters, CI pipelines — that is not practical.

html-saurus fills that gap:

| | Docusaurus | html-saurus |
|---|---|---|
| Runtime | Node.js | Java 17+ |
| Output | SPA (React) | Plain static HTML |
| Build time | ~10–30 s | < 1 s |
| Full-text search | Algolia / local plugin | Embedded Lucene (Japanese-aware) |
| Serves existing `docs/` as-is | yes | yes |
| `_category_.json`, numeric prefixes | yes | yes |
| Admonitions (`:::note` etc.) | yes | yes |

## Compatibility with Docusaurus projects

html-saurus reads your existing Docusaurus project without any modifications:

- `docs/` directory structure is preserved as the navigation tree
- Numeric prefixes (`010_Intro/`, `020_Setup.md`) are stripped from labels
- `_category_.json` provides section labels
- Same-name directory/file collapsing (`foo/foo.md` → section index)
- `intro.md` at the docs root becomes the landing page
- Frontmatter `id:` and `title:` fields are respected
- Admonitions: `:::note`, `:::tip`, `:::info`, `:::warning`, `:::danger`

## Requirements

- Java 17 or later
- Maven 3.8+ (for building from source only)

## Build

```bash
cd html-saurus
mvn install
```

The fat JAR is produced at `target/html-saurus.jar` (no other dependencies needed at runtime).

## Usage

### Single-project mode

Point html-saurus at any Docusaurus project directory:

```bash
# Serve the docs/ directory in the current Docusaurus project
java -jar html-saurus.jar --serve

# Specify a project directory explicitly
java -jar html-saurus.jar /path/to/my-docusaurus-project --serve

# Build static HTML only (no server)
java -jar html-saurus.jar /path/to/my-docusaurus-project

# Watch for file changes and rebuild automatically
java -jar html-saurus.jar --serve --watch

# Use a custom port (default: 8080)
java -jar html-saurus.jar --serve --port 3000
```

Generated HTML is written to `<project>/static-html/`.
The Lucene search index is written to `<project>/search-index/`.

### Portal mode — serve multiple Docusaurus projects at once

Portal mode scans a root directory for all Docusaurus projects (any subdirectory containing `docs/` and `docusaurus.config.js` or `docusaurus.config.ts`) and serves them together under a single server with a project index page.

```bash
# Run from the parent directory that contains multiple Docusaurus projects
cd ~/works
java -jar html-saurus.jar --portal-mode --serve --port 3100

# With live rebuild on file changes
java -jar html-saurus.jar --portal-mode --serve --watch --port 3100
```

- Portal index: `http://localhost:<port>/`
- Each project: `http://localhost:<port>/<project-name>/`
- Cross-project full-text search: `http://localhost:<port>/search?q=<query>`

## Command-line options

| Option | Description |
|--------|-------------|
| `[dir]` | Project directory to process (default: current directory) |
| `--portal-mode` | Discover and serve all Docusaurus projects under the root |
| `--serve` | Start an HTTP server after building |
| `--watch` | Watch for file changes and rebuild automatically |
| `--port <n>` | HTTP port (default: 8080) |

## Full-text search

- Embedded [Apache Lucene](https://lucene.apache.org/) — no external search service needed
- Japanese morphological analysis via the Kuromoji (IPAdic) analyzer
- Search fields: `title` (boost ×3), frontmatter `id:` (boost ×5), `body` (boost ×1)
- Per-page real-time search dropdown in the navbar
- SSR search results page at `/search?q=...` with pagination (100 results/page), breadcrumb, and summary

## Architecture

```
src/main/java/com/scivicslab/htmlsaurus/
├── Main.java          # CLI parsing, single/portal mode dispatch
├── SiteBuilder.java   # Markdown → HTML, tree building, CSS/JS embedding
├── SearchIndexer.java # Lucene index writer
├── SearchServer.java  # HTTP server for single-project mode
└── PortalServer.java  # HTTP server for portal mode (routing, build API, SSR search)
```

Uses the JDK built-in `com.sun.net.httpserver.HttpServer` — no external server dependency.

## License

Apache License 2.0
