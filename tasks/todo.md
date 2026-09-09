# Import タブに Web ページと Video (YouTube) を追加する

quarkus-english-toolkit の Web / Video インポートを html-saurus のポータル Import タブへ移植する。

## 現状

Import タブは `#import-type` ドロップダウンで PDF と Word を切り替える。PDF は
`JobRegistry` 上の非同期ジョブ (`PdfImportJob`)、Word は同期の一発変換 (`WordImportService`)。
どちらも `<project>/docs/<destPath>/<stem>/<stem>.md` に書き、画像は同じディレクトリへ置き、
最後に `runBuildStage(proj, "html")` と `"index"` を回す。

移植元 (quarkus-english-toolkit):
- `WebArticleClient` — jsoup でページを取得し、boilerplate を除去して `<p>` を抽出する。
- `TranscriptClient` — Whisper transcript サーバ (`192.168.5.13:8003`) に POST し、
  `GPU_BROKER_URL` があれば `whisper-transcript` キュー経由で投げる。

## 設計判断

- **Web は同期**、**Video は非同期ジョブ**。ページ取得は数秒、Whisper は数分かかるため。
- Video は既存の `importJobs` レジストリに `kind="video"` で載せる。`Job` は既に
  `kind()` と `phase()` を持つので、レジストリ側の変更は要らない。
- 出力先ディレクトリ名 (`stem`) はファイル名が存在しないため、ページ/動画のタイトルを
  スラグ化して決める。フォームの Filename 欄で上書きできる。
- 記事中の `<img>` は実ファイルとして取得し、`.md` の隣に保存して `![](name)` に書き換える。
  PDF・Word のインポートが画像を実体で保存しているのに揃える (english-toolkit は URL のまま持つが、
  そちらは画面にカードを出すだけで Markdown を書かないため事情が違う)。

## 手順

- [x] 1. `pom.xml` に `org.jsoup:jsoup:1.18.3` を追加する
- [x] 2. `WebImportService.java` を新規作成 — `WebArticleClient` の抽出ロジックを CDI 抜きで移植し、
      本文中の `<img>` を取得して `img1.png` 等にリネーム、`![](name)` 参照を書き換え、
      frontmatter (`title`, `source_url`) 付き Markdown を組み立てる
- [x] 3. `WebImportServiceTest.java` — 固定 HTML 文字列からの抽出をユニットテスト (ネットワーク非依存)
- [x] 4. `TranscriptClient.java` を新規作成 — 移植。コンストラクタで `TRANSCRIPT_SERVER_URL` と
      任意の `GpuBrokerClient` を受け取る。`parseResult` は static でテスト可能にする
- [x] 5. `TranscriptClientTest.java` — 定型 JSON からの `parseResult` をユニットテスト
- [x] 6. `VideoImportJob.java` を新規作成 — `JobRegistry.Work`。transcribe → Markdown 書き出し → 再ビルド
- [x] 7. `PortalServer.java` サーバ側:
      - `buildTranscriptClient()` を `buildOcrClients()` の隣に追加
      - `POST /api/import/web`、`POST /api/import/video/start` を追加
      - `/api/import/pdf/{jobs,status,stop,clear}` を `/api/import/{jobs,status,stop,clear}` に一般化
        (レジストリは元から全種別を保持しているため)
      - `importJobJson` に `kind` と `phase` を追加
- [x] 8. `PortalServer.java` 画面側:
      - `#import-type` に Web / Video の `<option>` と 2 つの `.import-panel` セクションを追加
      - `startWebImport()` / `startVideoImport()`、`importFormFieldStore` の欄一覧、
        `populateImportProjects` の対象 id、`importJobLine` の kind 分岐
- [x] 9. `ImportTabE2E.java` に Web / Video パネルの表示切替チェックを追加
- [x] 10. `rm -rf target` してから `mvn install` (`-DskipTests` は使わない)、
      ポータルを再起動して実機で Web 1 件・Video 1 件をインポートし、書かれた `.md` を確認する

## Review

### 追加したもの

Import タブの型ドロップダウンに Web page と Video が加わった。Web はページ取得が数秒で終わるため
Word と同じ同期処理、Video は Whisper が数分かかるため PDF と同じ `JobRegistry` 上の非同期ジョブ。
どちらも `<project>/docs/<destPath>/<stem>/<stem>.md` に書き、画像を同じディレクトリへ置く。
ファイル名の元になる `stem` は、ページ/動画のタイトルをスラグ化して決める (フォームの Filename 欄で上書き可)。

新規クラス: `WebImportService` (jsoup 抽出 + 画像ダウンロード)、`TranscriptClient` (転写サーバ呼び出し)、
`VideoImportJob` (ジョブ本体)、`ImportOutcome` (ジョブ種別を問わない結果レコード)。

### 移植元から変えた点

- 記事中の `<img>` を実ファイルとして取得し、`.md` の隣に保存する。移植元は URL のまま画面のカードに
  出すだけだったが、こちらは Markdown 文書を書くので PDF・Word の挙動に揃えた。
- `<a href>` を `[text](url)` として保持する。参照資料として読む文書では URL が要る。
  飛び先の有無は `absUrl` でなく生の `href` 属性で判定する (`absUrl` はページ内アンカー `#top` を
  ページ自身の URL に解決してしまうため)。
- 見出し `h1`-`h6` を Markdown 見出しとして保持する。html-saurus の文書は見出しで目次が作られるため。
- 除去する boilerplate から `figure`/`figcaption` を外した (図が入っているため)。代わりに Jetpack の
  `.sharedaddy` など、本文の後ろに付く共有・関連記事ウィジェットを加えた。
- 転写を 1 本のテキストに繋げず、文末で 400 文字以上ごとに段落へ分ける。

### 既存コードへの変更

- ジョブ用エンドポイントを `/api/import/pdf/{jobs,status,stop,clear}` から `/api/import/{...}` へ一般化。
  レジストリは元から全種別を保持していたので、URL だけが PDF 名義で残っていた。
- `PdfImportJob.Result` を `ImportOutcome` へ切り出し、ジョブ JSON に `kind` と `phase` を追加。
  Batch Job の行は kind で文言を分ける (PDF はページ数、Video は phase テキスト)。
- gpu-broker 接続を `GPU_BROKER` 一つに集約し、OCR と転写で共有する。
- 各インポートハンドラに散っていた project / destPath の解決を `resolveImportTarget` に集約。

### 検証

- ユニットテスト 24 件新規 (`WebImportServiceTest` 20、`TranscriptClientTest` 9 のうち新規分)、全件緑。
- `ImportTabE2E` 9 件全て緑。Project ドロップダウンが空になるバグ (`var` の巻き上げで
  `populateImportProjects()` 呼び出し時に `undefined`) をこの E2E が捕まえた。関数宣言に変えて修正済み。
- 実機確認: NCBI Insights の記事を Web インポートし、リンク・見出し・画像が保持され共有ウィジェットが
  除去されることを確認。YouTube の動画を Video インポートし、転写と `thumbnail.jpg` が書かれることを確認。

### 未対応

- 稼働中の 28001 / 28012 には反映していない。`~/works/html-saurus-2.3.0-SNAPSHOT-260909-1055.jar` を
  配備済みだが、`html-saurus.jar` のリンク切り替えと再起動はユーザーの操作を待つ。
