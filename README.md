# html-saurus

**html-saurus** is a lightweight static site generator and documentation server for [Docusaurus](https://docusaurus.io/)-style Markdown projects, written in pure Java with no external server dependencies. Unlike Docusaurus itself, it generates plain static HTML — no SPA, no Node.js required.

## Features

- **Static site generation** — converts Markdown files to HTML, preserving Docusaurus directory conventions (numeric prefixes, `_category_.json`, same-name directory/file collapsing)
- **Full-text search** — embedded [Apache Lucene](https://lucene.apache.org/) with Japanese (Kuromoji) morphological analysis; searches by title, body, and document ID
- **Portal mode** — discovers all Docusaurus projects under a root directory and serves them together under a single HTTP server with a project index page
- **Live rebuild** — watches for file changes and rebuilds automatically (`--watch`); per-project Rebuild button available on every page and on the portal
- **Admonitions** — renders `:::note`, `:::tip`, `:::info`, `:::warning`, `:::danger` blocks
- **CSS themes** — Default, Warm, Blue, Green, Red light themes with a switcher in the navbar
- **SSR search page** — server-side rendered search results page at `/search?q=...` with pagination (100 results/page), project label, title, breadcrumb, and summary
- **Per-page search dropdown** — real-time search dropdown in the navbar of every generated page

## Requirements

- Java 17 or later
- Maven 3.8+ (for building from source)

## Build

```bash
cd html-saurus
mvn install
```

The fat JAR is produced at `target/html-saurus.jar`.

## Usage

### Single-project mode

```bash
# Build and serve the docs/ directory in the current directory
java -jar html-saurus.jar --serve

# Specify a project directory explicitly
java -jar html-saurus.jar /path/to/my-docusaurus-project --serve

# Build only (no server)
java -jar html-saurus.jar /path/to/my-docusaurus-project

# Watch for changes and rebuild automatically
java -jar html-saurus.jar --serve --watch

# Use a custom port (default: 8080)
java -jar html-saurus.jar --serve --port 3000
```

Generated HTML is written to `<project>/static-html/`.
The Lucene index is written to `<project>/search-index/`.

### Portal mode

Portal mode discovers every Docusaurus project (directories containing `docs/` and `docusaurus.config.js` or `docusaurus.config.ts`) under the current directory (or a specified root) and serves them all from one server.

```bash
# Run from the parent directory that contains multiple Docusaurus projects
cd ~/works
java -jar html-saurus.jar --portal-mode --serve --port 3100

# With file watching
java -jar html-saurus.jar --portal-mode --serve --watch --port 3100
```

The portal is accessible at `http://localhost:<port>/`.
Each project is served at `http://localhost:<port>/<project-name>/`.
Full-text search across all projects: `http://localhost:<port>/search?q=<query>`.

## Command-line options

| Option | Description |
|--------|-------------|
| `[dir]` | Project directory to process (default: current directory) |
| `--portal-mode` | Discover and serve all Docusaurus projects under the root |
| `--serve` | Start an HTTP server after building |
| `--watch` | Watch for file changes and rebuild automatically (implies `--serve`) |
| `--port <n>` | HTTP port (default: 8080) |

## Project conventions

html-saurus follows Docusaurus v2/v3 documentation conventions:

- **Numeric prefixes** — directories and files may be prefixed with `NNN_` for ordering (e.g., `010_Introduction/`); prefixes are stripped from display labels
- **`_category_.json`** — used for category labels (`{"label": "My Section"}`)
- **Same-name collapse** — a directory `foo/` with a file `foo.md` inside renders `foo.md` as the index page of that section
- **`intro.md`** — if present at the root of `docs/`, used as the landing page; otherwise an `index.html` redirect to the first page is generated automatically

## Search

- Lucene index is built on every startup and on every manual Rebuild
- Japanese text is analyzed with the Kuromoji (IPAdic) morphological analyzer
- Search fields: `title` (boost ×3), `doc_id` / frontmatter `id:` field (boost ×5), `body` (boost ×1)
- Per-project search returns results with title, breadcrumb (directory hierarchy), and summary

## Architecture

```
src/main/java/com/scivicslab/htmlsaurus/
├── Main.java          # Entry point, CLI parsing, single/portal mode dispatch
├── SiteBuilder.java   # Markdown → HTML conversion, tree building, CSS/JS embedding
├── SearchIndexer.java # Lucene index writer
├── SearchServer.java  # HTTP server for single-project mode
└── PortalServer.java  # HTTP server for portal mode (routing, build API, SSR search)
```

HTTP serving uses the JDK built-in `com.sun.net.httpserver.HttpServer` — no additional server dependency required.

## License

Apache License 2.0
