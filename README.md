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

### 1. Build-only mode (primary use case)

Convert a Docusaurus project's `docs/` to static HTML and exit. No server is started.

```bash
# Build the project in the current directory
cd /path/to/my-docusaurus-project
java -jar html-saurus.jar

# Or specify the project directory explicitly
java -jar html-saurus.jar /path/to/my-docusaurus-project
```

Generated HTML is written to `<project>/static-html/`.
The Lucene search index is written to `<project>/search-index/`.

This is the fastest way to regenerate output after editing Markdown files. Useful in CI pipelines, cron jobs, or any environment where you do not need a running server.

### 2. Single-project server mode

Build and start a development HTTP server for one project:

```bash
# Serve the current directory's project (default port: 8080)
java -jar html-saurus.jar --serve

# Specify a project directory and a custom port
java -jar html-saurus.jar /path/to/my-docusaurus-project --serve --port 3000

# Watch for file changes and rebuild automatically
java -jar html-saurus.jar --serve --watch
```

### 3. Portal mode — serve multiple Docusaurus projects at once

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
| `--html` | Build static HTML only, then exit (no server) |
| `--index` | Build the Lucene full-text index only, then exit |
| `--embedding` | Build the embedding (RAG) vectors only, then exit |

### Building the three stages separately

A full build has three independent stages: **(1)** static HTML, **(2)** the Lucene full-text
index, and **(3)** the embedding (RAG) vectors. Each can be run on its own with `--html`,
`--index`, and `--embedding`. When any of these is given, html-saurus runs **only** the selected
stages (in dependency order html → index → embedding) and exits without starting a server. Flags
combine, and they work in `--portal-mode` too (HTML/index run per project; embedding runs across
all projects at once).

```bash
# Stage 1 only — refresh the HTML, e.g. after editing Markdown
java -jar html-saurus.jar /path/to/project --html

# Stages 1 + 2 — HTML and full-text index, without touching embeddings
java -jar html-saurus.jar /path/to/project --html --index

# Stage 3 only — rebuild RAG vectors (needs the embedding server; warns and skips if unreachable)
java -jar html-saurus.jar /path/to/project --embedding

# All three stages across every project under a portal root
java -jar html-saurus.jar /path/to/works --portal-mode --html --index --embedding
```

The embedding stage depends on the shared embedding server (`EMBEDDING_SERVER_URL`); if it is
unreachable, that stage logs a warning and skips, leaving the HTML and full-text index unaffected.
This is why the stages are separable: an HTML refresh never has to wait on, or fail because of, the
embedding server.

## Full-text search

- Embedded [Apache Lucene](https://lucene.apache.org/) — no external search service needed
- Japanese morphological analysis via the Kuromoji (IPAdic) analyzer
- Search fields: `title` (boost ×3), frontmatter `id:` (boost ×5), `body` (boost ×1)
- Per-page real-time search dropdown in the navbar
- SSR search results page at `/search?q=...` with pagination (100 results/page), breadcrumb, and summary

## Configuration file reference

html-saurus reads several Docusaurus project files automatically. None of them are required — the tool falls back gracefully when any are absent.

### `docusaurus.config.ts` / `docusaurus.config.js`

| Field | Used for |
|-------|----------|
| `themeConfig.navbar.title` | サイト名（ページ上部ヘッダーおよびタブタイトル） |
| `favicon` | ファビコン画像のパス（`static/` ディレクトリからの相対パス） |
| `themeConfig.navbar.items[].sidebarId` + `.label` | ナビバーのセクション表示順序とラベル |
| `themeConfig.navbar.items[].docId` + `.label` | 同上（docId 形式） |
| `url` | サイトの公開 URL（例: `https://example.com`）。`sitemap.xml`・`rss.xml`・`feed.json` の生成に使用。未設定時はこれらのファイルを生成しない |
| `i18n.defaultLocale` | デフォルトロケールの判定 |
| `i18n.locales` | 利用可能なロケール一覧（言語切り替え UI） |

`docusaurus.config.ts` が存在しない場合、サイト名はプロジェクトディレクトリ名、ナビバー順序はファイルシステム順にフォールバックする。

### `sidebars.ts` / `sidebars.js`

`{ type: 'autogenerated', dirName: '...' }` エントリからサイドバー ID → ディレクトリ名のマッピングを抽出する。
`docusaurus.config.ts` の `sidebarId` と組み合わせて、ナビバーの表示順序を決定する。

### `i18n/<locale>/docusaurus-theme-classic/navbar.json`

| Key | Used for |
|-----|----------|
| `"title": { "message": "..." }` | サイト名の翻訳（ページ上部ヘッダー） |
| `"item.label.<original>": { "message": "..." }` | ナビバー項目ラベルの翻訳 |

**デフォルトロケール含む全ロケール**で読み込まれる。`docusaurus.config.ts` に英語ラベルを書き、`navbar.json` で日本語ラベルを上書きするという Docusaurus の一般的なパターンに対応している。

### `i18n/<locale>/docusaurus-plugin-content-docs/current/`

各ロケールの Markdown ソースディレクトリ。`--locale <code>` を指定してビルドすると、このディレクトリがドキュメントのルートとして使われる。

### `_category_.json` （docs 以下の各サブディレクトリ）

`"label": "..."` フィールドをナビゲーションツリーのカテゴリ名として使用する。ファイルが存在しない場合は、数値プレフィックス（`01_`、`010_` など）を除いたディレクトリ名を使用する。

### `static/<path>` （ファビコン）

`docusaurus.config.ts` の `favicon:` フィールドで指定されたパスを `<projectRoot>/static/` 以下から読み込み、Base64 data URL として各ページの `<link rel="icon">` に埋め込む。`favicon:` フィールドがない場合、またはファイルが存在しない場合はファビコンなしで動作する。

### プロジェクトルート直下のカスタマイズファイル（プロダクションモードのみ）

| ファイル | 用途 |
|----------|------|
| `html-saurus.css` | 全ページに追加される CSS（`<style>` タグとして `<head>` に挿入） |
| `html-saurus-header.html` | 各ページの `<body>` 直後に挿入される HTML |
| `html-saurus-footer.html` | 各ページの `</body>` 直前に挿入される HTML |
| `html-saurus-toc-footer.html` | 右サイドカラム（ページ目次）の下部に挿入される HTML。省略時は `url:` が設定されていれば RSS/JSON Feed リンクが自動生成される |

これらのファイルは開発モード（`--serve` のみ）では読み込まれない。`--production` フラグを付けたビルドまたはポータルモードで有効になる。

#### ロケール別カスタマイズファイル

ファイル名に `.{locale}` を挟むことでロケール別の内容を指定できる。例えば日本語ロケールビルドには `html-saurus-toc-footer.ja.html`、英語ロケールビルドには `html-saurus-toc-footer.en.html` が使われる。ロケール指定ファイルが存在しない場合は `html-saurus-toc-footer.html`（ロケール指定なし）にフォールバックする。他のカスタマイズファイル（`html-saurus-header.html` 等）も同様のルールに従う。

#### RSS / JSON Feed リンクの自動生成（`html-saurus-toc-footer.html` 省略時）

`docusaurus.config.ts` に `url: 'https://example.com'` が設定されている場合、`html-saurus-toc-footer.html` が存在しないとき各ページの右サイドカラム下部に RSS と JSON Feed へのリンクバッジが自動生成される。

```
[ RSS ]  [ JSON Feed ]
```

リンク先はそれぞれ `rss.xml`（RSS 2.0）と `feed.json`（JSON Feed v1.1）。どちらもビルド時にサイトルートへ出力される。`html-saurus-toc-footer.html` を置くと自動生成リンクの代わりにそのファイルの内容が使われるため、独自のボタン画像や説明文に差し替えることができる。

---

## アクセスログ（プロダクションモード）

プロダクションモード（`html-saurus-production-server` の `serve`）は、リクエストごとに 1 行、Apache の `combined` 形式でアクセスログを書きます。

```
203.0.113.5 - - [25/Nov/2026:14:30:12 +0900] "GET /foo/ HTTP/1.1" 200 5123 "https://example/" "Mozilla/5.0 ..."
```

`LogFormat "%h %l %u %t \"%r\" %>s %b \"%{Referer}i\" \"%{User-Agent}i\""` と同じ並びです。`%l` と `%u` は常に `-`、`%b` はレスポンス本文のバイト数で 0 のときは `-`、時刻はリクエストを受けた時刻をローカルタイムゾーンのオフセット付きで書きます。リクエスト行・Referer・User-Agent は Apache と同じ規則（`\"`、`\\`、制御文字と ASCII 外のバイトは `\xHH`）でエスケープします。

静的ファイル、301 のリダイレクト、404/403、`/search` と各ロケールの `/<locale>/search`、ハンドラが失敗したリクエストも、同じ仕組み（登録した全 context に付ける 1 つの `Filter`）で 1 行ずつ記録します。ハンドラが応答を返す前に例外で終わったリクエストは `500`、何も返さずに終わったものは `000` と書きます。HTTP のリクエスト行として読めない接続（サーバが 400 を返す前の段階）は、Filter まで届かないため記録されません。

### オプション

| オプション | システムプロパティ | 既定値 |
|---|---|---|
| `--no-access-log` | `htmlsaurus.access.log=off` | ログを書く |
| `--log-dir <dir>` | `htmlsaurus.log.dir` | 起動したときのカレントディレクトリの `logs/` |
| `--access-log <file\|->` | `htmlsaurus.access.log` | `access.log`（`--log-dir` の中。絶対パスならそのまま。`-` は標準出力） |
| `--access-log-response-time` | `htmlsaurus.access.log.response.time=true` | 付けない |
| `--access-log-trusted-proxy <list>` | `htmlsaurus.access.log.trusted.proxies` | なし（どのヘッダも信用しない） |

コマンドラインのオプションがシステムプロパティより優先します。`--access-log-response-time` を付けると、各行の最後にリクエストの処理時間（マイクロ秒）を 1 項目足します。既定では付けないので、1 行は Apache の combined 形式とバイト単位で同じです。

```bash
java -jar html-saurus-production-server.jar serve -d /data/site -p 8080 --log-dir /var/log/html-saurus
# コンテナ: 標準出力へ
java -jar html-saurus-production-server.jar serve -d /data/site --access-log -
```

### クライアントのアドレス（`%h`）

既定では TCP の接続元アドレスを書きます。リバースプロキシや Ingress の後ろでは接続元がプロキシになるため、`--access-log-trusted-proxy 10.0.0.0/8,192.168.1.5` のようにプロキシのアドレス（IP リテラルか CIDR）を指定すると、接続元がそのプロキシのときだけ `X-Forwarded-For` を読みます。ヘッダは右から読み、信用するプロキシのアドレスを飛ばして、最初に現れた信用しないアドレスをクライアントとします。接続元が信用するプロキシでなければ、ヘッダに何が書いてあっても無視します（クライアントが自分でヘッダを付けても、ログのアドレスは変えられません）。ヘッダに IP リテラル以外が混ざっているときも、接続元のアドレスを書きます。

### ファイルの管理（Apache と同じ）

- ファイルは追記で開いたままにし、新規作成のモードは `0640` です。既存のファイルのモードは変えません。ディレクトリがなければ作ります。
- logrotate に対応します。書き込む前に、1 秒に 1 回以内でパスの指すファイルを調べ、名前が変えられた・消された場合は、パスをもう一度開いて新しいファイルに書きます。`copytruncate` は要りません。
- 書き込みに失敗しても、配信は止まりません。失敗は数えて、最初の 1 回だけ標準エラーに出し、終了時に失われた行数を出します。次の行は 1 秒以内に再び開き直そうとします。
- 書き込みはログ専用の 1 つのアクターが行うので、複数のリクエストの行が混ざることはありません。

logrotate の設定例（`/etc/logrotate.d/html-saurus`）:

```
/var/log/html-saurus/access.log {
    weekly
    rotate 8
    compress
    delaycompress
    missingok
    notifempty
    create 0640 html-saurus html-saurus
}
```

`postrotate` で再読み込みの信号を送る必要はありません。サーバが次の 1 秒以内に新しいファイルを開きます。

### 注意

- Apache と同じく、リクエスト行をクエリ文字列ごと記録します。したがって、`/search?q=...` の検索語がログに残ります。ログの読み取り権限を絞ってください（既定の `0640` はそのためです）。
- ログの置き場所（`--log-dir`）は、起動するユーザーが書き込めるディレクトリにしてください。k8s やコンテナでは、ボリュームを付けるか `--access-log -` で標準出力に出します。

## Architecture

```
src/main/java/com/scivicslab/htmlsaurus/
├── Main.java              # CLI parsing, single/portal mode dispatch
├── SiteBuilder.java       # HTML rendering, page orchestration
├── MarkdownConverter.java # Markdown → HTML, frontmatter parsing, admonitions
├── NavTreeBuilder.java    # Navigation tree construction, Docusaurus config reordering
├── SiteNode.java          # Navigation tree node (record)
├── SearchIndexer.java     # Lucene index writer
├── SearchServer.java      # HTTP server for single-project mode
├── PortalServer.java      # HTTP server for portal mode (routing, build API, SSR search)
├── LuceneSearcher.java    # Long-lived Lucene DirectoryReader (shared across requests)
└── HttpUtils.java         # Shared HTTP utilities (respond, escapeHtml, contentType, …)
```

Uses the JDK built-in `com.sun.net.httpserver.HttpServer` — no external server dependency.

## Testing

### Test structure

```
src/test/java/com/scivicslab/htmlsaurus/
├── UrlRuleTest.java           # URL generation rules (numeric prefix, dev/prod suffix)
├── SidebarTest.java           # Sidebar rendering, Pattern 1/2 categories
├── ModeTest.java              # CLI mode behaviour (build-only, dev, portal, production)
├── I18nTest.java              # i18n: locale prefixes, lang attribute, language switcher
├── SearchIndexerTest.java     # Lucene index: path field, locale prefix, cleanRelPath utility
├── PortalOpenLinkTest.java    # Portal mode: project link generation
└── ProductionModeE2ETest.java # End-to-end tests with Playwright (requires real data)
```

There are two tiers of tests:

| Tier | Files | Description |
|------|-------|-------------|
| **Unit / integration** | `*Test.java` (all except `ProductionModeE2ETest`) | Spin up real `SiteBuilder` / `SearchServer` instances against `TempDir`. No mocks, no Docker, no external processes. Run with `mvn test`. |
| **End-to-end** | `ProductionModeE2ETest.java` | Full browser tests via Playwright Java. Require a real Docusaurus project on disk (`nigsc_homepage2`). Skipped automatically when the project is absent. Run with `mvn test`. |

### Running tests

```bash
# すべてのテスト（ユニット＋E2E）を実行
cd html-saurus
rm -rf target && mvn install

# テストのみ（ビルド済みクラスがある場合）
mvn test

# カバレッジレポートを確認（target/site/jacoco/index.html）
mvn test  # JaCoCo は mvn test 実行時に自動生成される
open target/site/jacoco/index.html
```

E2Eテスト (`ProductionModeE2ETest`) は `/home/devteam/works/nigsc_homepage2/docs` が存在しない環境では `Assumptions.assumeTrue` により自動的にスキップされる。テスト失敗にはならない。

### テスト設計の方針

**何をテストするか**

- URL生成ルール（数値プレフィックスの除去、dev/prod モードのサフィックス）
- サイドバー構造（Pattern 1: `dir/dir.md` のみ、Pattern 2: `dir/dir.md` + サブディレクトリ）
- CLIモードごとの出力の違い（Rebuild ボタンの有無、テーマセレクターの有無など）
- i18n: ロケールプレフィックス、`html lang` 属性、言語切り替えUI
- Lucene インデックスのパスフィールド（`cleanRelPath` の正確さ）
- ポータルモードのプロジェクトリンク

**何をテストしないか**

- Playwright がなければ動かない機能は `ProductionModeE2ETest` に限定する
- Docker・外部サービス・モックDB は使用しない（テスト環境と本番の乖離を防ぐため）
- 純粋な getter / setter / 定数はテストしない

**新機能を追加するとき**

1. 対応する `*Test.java` にテストケースを追加してから実装する（TDD）
2. ブラウザ操作が必要な検証は `ProductionModeE2ETest` に追加する
3. テスト追加後に `mvn test` でグリーンを確認してからコミットする

**E2Eテストを実行したいとき**

```bash
# nigsc_homepage2 が ~/works/ 以下に存在する場合、mvn test で自動実行される
ls /home/devteam/works/nigsc_homepage2/docs   # 存在確認

# Playwright ブラウザが未インストールの場合は初回のみ必要
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI \
  -D exec.args="install --with-deps chromium"
```

## License

Apache License 2.0
