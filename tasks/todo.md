# 箇条書きは入れ子も含めて、最も外側のリスト全体を 1 ブロックにする（2026-10-18）

## 経緯
- 最初の変更は「入れ子のない箇条書き」だけを 1 ブロックにした。実際の本の箇条書きは、項目の下に小項目が入れ子になっているものが大半で、帯が項目ごとに付いたままだった（CISSP Chap01 p001-010 の先頭のリストは 8 項目に帯 20 個）。
- もう 1 つの原因: 新しい jar を動かしても、`static-html` の既存ページには古いスクリプトが埋め込まれたままで、HTML を再ビルドするまで変わらない。

## Plan
- [x] 最も外側の `ul`/`ol` を 1 ブロックにし、入れ子は 1 段ごとに 2 文字インデントした行で送る
- [x] 訳の表示は `white-space: pre-wrap`（インデントを保つ）
- [x] 6000 文字を超えるリストは従来どおり項目ごと
- [x] 翻訳のプロンプトに「改行、インデント、先頭の `- ` や番号を保つ」を足す
- [x] 本物のページで確認（ブロック 77 → 39、外側のリスト 5 個に帯 5 個、リスト内の帯 0、失敗 0）

## Review
- HTML の再ビルドは `doc_Base011` だけ済み。他のプロジェクトは Update が要る。

---

# ページ翻訳を Batch Job にして、GPU broker のジョブ待ち行列に流す（2026-10-18）

## 方針（ユーザー決定）
- 翻訳は Import と同じ流れにする: broker の `POST /jobs/{キュー}` に `FOREGROUND` で投入し、`GpuBrokerClient` の流量制御に任せる。
- ブラウザは全ブロックの文面を 1 回で渡し、サーバが 1 つの Batch Job（kind `translate`）として走らせる。
- 訳はキャッシュに入る。ブラウザは進み具合を問い合わせ、訳せたブロックから差し込む。

## Plan
- [x] `TranslationClient.translateViaBroker`: キュー名は `chat-` + モデル名（`/` は `-`）。`FOREGROUND`、待ちにタイムアウトを付けない
- [x] `PageTranslationJob`: キャッシュ済みは飛ばす・同文は 1 回だけ投入・失敗は数えて続行・停止で打ち切る
- [x] `POST /api/translate-page`（ジョブ開始）と `POST /api/translate-lookup`（キャッシュから引く）
- [x] `GPU_BROKER_URL` が無いときは直接呼び出しを 1 件ずつ（起動時の判断。実行時の切り替えはしない）
- [x] `PageScripts`: 1 回で渡し、進み具合を問い合わせ、訳せた順に差し込む。失敗は「翻訳失敗」と表示。Hide で停止
- [x] Batch Job タブに `translate` の行（`n / 全体`）
- [x] テスト: ジョブ（偽の翻訳器）、キュー名、ブラウザ（ヘッドレス）で 1 ページ通し
- [x] `rm -rf target && mvn install`（lib → portal-server の順）

## Review
- ページ翻訳は Batch Job（kind `translate`）になった。ブラウザは全ブロックを 1 回で渡し、`/api/import/status` と `/api/translate-lookup` を 1.5 秒ごとに引いて、訳せたブロックから差し込む。ジョブの状態を先に読み、終わっていたら残りを「翻訳失敗」と表示する。
- キュー名は `chat-` + モデル名（`/` は `-`）。待ちにタイムアウトは付けず、投入は `GpuBrokerClient` の流量制御（キューごとに 10 件）で進む。`FOREGROUND`。
- 本物のページ（CISSP Chap01 p001-010、58 ブロック）で確認: 開始 → 進み具合 → 完了。ブラウザを閉じてもジョブは 58 / 58 まで進み、もう一度押すと全部キャッシュから 1.7 秒で出る。失敗 0。
- 確認時のモデルは Settings の `qwen3.8-flash-next-iq3_xxs-radeon`（処理枠 1）で、58 ブロックに約 5 分。落ちずに順に進んだ。gemma（32 枠）なら速い。
- 単体テスト: lib 168 件、portal-server 161 件（ジョブ 6 件、キュー名 1 件を追加）。commit・push・稼働中インスタンスの入れ替えは未実施。

---

# 単純な箇条書きは 1 ブロックとして翻訳する（2026-10-17）

## Plan
- [x] `PageScripts.java`: 入れ子のない `ul`/`ol` を 1 ブロックにし、翻訳の帯をリストの後ろに 1 つだけ置く
- [x] 項目は 1 行ずつ（`ul` は `- `、`ol` は `1. `）にして送り、訳も改行を保って表示する（`white-space: pre-line`）
- [x] 入れ子のある箇条書きは従来どおり項目ごと（入れ子の中の単純なリストは、それ自体が 1 ブロック）
- [x] ヘッドレス Chromium で確認（`/api/translate` は差し替え）。実モデルが行構造を保つことも確認

## Review
- 単純な `ul`・`ol`・段落入りの箇条書きは、それぞれ 1 回の翻訳と 1 つの帯になった。入れ子のある箇条書きは親項目・入れ子のリスト・兄弟項目の 3 つに分かれる。
- HTML の各ページにスクリプトが埋め込まれているので、反映には jar の入れ替えと、各プロジェクトの HTML の再ビルドが要る。

---

# 翻訳モデルを AI workspace の Settings から取る（2026-10-17）

## Plan
- [x] `TranslationClient`: 翻訳のたびに `AI_WORKSPACE_URL/api/settings/translation-model` を引く（短い間だけ保持して段落ごとの HTTP を避ける）
- [x] 引けない・空のときは環境変数 `TRANSLATION_MODEL`、なければ既定値
- [x] テスト: 応答の解釈、引けないときの退避（`com.sun.net.httpserver` で受ける）
- [x] `rm -rf target && mvn install`

## Review
- モデルは「AI workspace の Settings → `TRANSLATION_MODEL` → 既定値」の順。AI workspace が無い・答えない・空のときは次へ退避し、翻訳は止まらない。
- 5 秒だけ保持する（1 ページの数十段落ごとに HTTP を打たないため）。Settings で変えてから最長 5 秒で効く。
- 単体テスト154件通過（受け側は `com.sun.net.httpserver`）。commit・push・稼働中インスタンスの入れ替えは未実施。

---

# 左ペインに `Update All Projects` ボタンを1つ置き、全体更新をそこへ集約する

ヘッダ右端にある全プロジェクト対象の操作ボタン2つを画面から取り除き、works ディレクトリの
再走査・HTML・Lucene index・embedding をまとめて実行する `Update All Projects` ボタンを1つだけ、
左の開閉ペインに置く。処理は非同期で走らせる。

## 現状

全プロジェクトを対象にする操作ボタンは `Scan Works Dir` と `Reindex All` の2つで、どちらも
ページ最上部の `<header>` の `.header-right`（Theme セレクトの右隣）にある
(`PortalServer.java:1397-1400`)。左の開閉ペイン `<aside id="portal-sidebar">` の外にあるため
見つけにくい。左ペインの Projects タブは、検索欄、`<h2>Projects</h2>`、プロジェクト行
（段階セレクト＋`Update` ボタン）の順で並ぶ。

各ボタンが実際に回す段階は揃っていない。

| ボタン | 対象 | HTML | index | embedding |
|---|---|---|---|---|
| `Scan Works Dir` | 未登録のプロジェクトのみ | ○ | ○ | × |
| `Reindex All` | 全プロジェクト | × | ○ | × |
| プロジェクト行の `Update`（`All`） | 1プロジェクト | ○ | ○ | ○ |

全プロジェクトを3段階そろえて更新する入口は、REST API にも MCP ツールにも無い。

## 設計判断

- **画面のボタンは1つにする**。`Update All Projects` が、works ディレクトリの再走査から embedding
  まで全部を引き受ける。押す側が段階を選ぶ必要も、2つのボタンを順番に押す必要も無くなる。
- **REST エンドポイントと MCP ツールは残す**。`POST /api/reindex-all` と
  `POST /api/scan-works-dir`、および同名の MCP ツールは、index だけを速く回したい場面と、
  AI コーディングエージェントからの呼び出しで使う。画面から消すのはボタンであって API ではない。
- **非同期にする**。全プロジェクトの全段階は数十分かかる。同期で待つ `handleBuildStage` ではなく、
  既存の `BuildJob` と `GET /api/build-status?jobId=` を使う。`BuildJob` の `project` に
  `(all projects)`、`message` に進行中のプロジェクト名と段階を入れれば、`handleBuildStatus` と
  ポーリング側の JavaScript は今の形のまま使える。
- **順序は 再走査 → 全プロジェクトの html → 全プロジェクトの index → embedding 1回**。
  段階の順序は `Main.runSteps` に合わせる。`ensureSemanticVectors` は複数プロジェクトを
  まとめて受け取る API なので、プロジェクトごとに呼ばない。再走査を先頭に置くのは、
  新しく見つかったプロジェクトもその回の更新対象に入れるためである。
- **新しく見つかったプロジェクトは、その1回の中で2度ビルドされる**。`scanWorksDirCore` は
  未登録プロジェクトを HTML と index をビルドしてから登録する（`LuceneSearcher` アクターが
  index ディレクトリの存在を前提とするため、登録前にビルドが要る）。続く全プロジェクトの
  パスが同じものをもう一度ビルドする。新しいプロジェクトが見つかるのは稀であり、結果は
  正しいので、登録を遅延させる作り替えはしない。

## 手順

- [x] 1. `PortalServer` に `updateAllProjectsCore(BuildJob job)` を追加する。
      `scanWorksDirCore()` → 全プロジェクトの `Main.build` → 全プロジェクトの `Main.reindexAll`
      → `Main.ensureSemanticVectors(全ディレクトリ)` の順に実行し、各段階で `job.message` を
      `(3/12) doc_SCIVICS002 html` の形に更新する。最後に `invalidatePrerequisiteOfIndex()` を呼ぶ
- [x] 2. `POST /api/update-all-projects-async` を追加する（開発モードのみ）。`handleBuildAsync` と
      同じ形で `BuildJob` を作り、デーモンスレッドで手順1を走らせ、202 と jobId を返す
- [x] 3. 画面のヘッダから `scan-works-dir-btn`・`reindex-all-btn` と各 `<span class="build-status">` を
      取り除く。`doScanWorksDir`・`doReindexAll` の JavaScript も併せて取り除く
      （REST エンドポイントは残す）
- [x] 4. 左ペインの `<h2>Projects</h2>` の直下に `Update All Projects` ボタンと状態表示を1行置く
- [x] 5. JavaScript `doUpdateAllProjects(btn)` を追加する。POST で jobId を受け取り、1.5秒ごとに
      `/api/build-status` を読み、`message` と経過秒を状態表示に出す。完了で緑、失敗で赤
- [x] 6. ボタン行の CSS を足す
- [x] 7. `McpHandler` に `update-all-projects` ツールを追加し、手順1の core を呼ぶ
- [x] 8. `PortalSearchE2E` に、左ペインに `Update All Projects` が存在すること、ヘッダに無いこと、
      旧2ボタンが消えていることの確認を足す。`POST /api/update-all-projects-async` を叩く確認は
      入れない。このE2Eは接続先の稼働ポータルに対して走るため、押した時点でそのポータルの
      全プロジェクト更新が始まってしまう。押して確かめるのは使い捨てのポータル（手順10）で行う
- [x] 9. `rm -rf target && mvn install`（`-DskipTests` は使わない）
- [x] 10. 使い捨てのポータル（`/tmp/hs-verify` に見本プロジェクト2個、ポート 28099）を起動し、
      ヘッドレスブラウザ（`~/tools/headless-verify/hs_update_all_projects.js`）で左ペインの表示を
      確認したうえで `Update All Projects` を実際に押し、再走査を含めて動くことを見た。
      稼働中のポータル（28001）では未実施
- [x] 11. REST API 一覧 `HtmlSaurusApi_260802_oo01` と MCP ツール一覧 `HtmlSaurusMcp_260803_oo01` に
      新しいエンドポイントとツールを追記する。段階の選択理由は `RebuildStageChoice_260901_oo01` の
      範囲なので、必要ならそちらにも追記する

## 追記: ディレクトリが消えたプロジェクトを登録から外す

### 現状（追記時点）

`projects` リストから項目を取り除く処理がどこにも無く、起動時とスキャン時に足すだけだった。
そのため `doc_SCIVICS000` を `doc_Base001` へ改名したあと、稼働中のポータルは消えたディレクトリを
指す行を出し続け、その行のリンクはすべて404を返した。検索も、索引のパスごと消えた対象を返した。
さらに `Update All Projects` を押すと、消えたプロジェクトに対しても `Main.reindexAll` が走り、
`Files.createDirectories` が `~/works/doc_SCIVICS000/search-index` を作り直した。

### 設計判断

- **判定は `Main.findProjects` の結果との突き合わせで行う**。ディレクトリの存在を個別に確かめるのではなく、
  「今スキャンが見つけた名前の集合に無い登録を外す」とする。ディレクトリが消えた場合と、
  ディレクトリは在るが `docs/` か Docusaurus 設定を失った場合の両方が、これ1つで拾える。
- **外すのはスキャン処理の中**。`Update All Projects` は先頭でスキャンを呼ぶので、押せば追従する。
- **searcher は閉じてから actor を止める**。`LuceneSearcher` は無同期で actor が所有するため、
  閉じる操作もメッセージとして送る。actor を先に止めるとメッセージが破棄され、開いたままの
  Lucene reader が残る。プロジェクトは自分の名前の searcher に加えてロケールごとに
  `<project>:<locale>` の searcher を持つので、全部外す。
- **MCP の既定 searcher を `Supplier` にする**。`McpHandler` は起動時の先頭 searcher を保持していたが、
  それが外された actor だと MCP の検索が止まる。読むたびに現在値を返す形にし、スキャンが
  追加・削除をしたときに再計算する。
- **画面の再読み込みはジョブの `listChanged` で判定する**。行数の比較では、1つ増えて1つ減った回
  （まさに改名の場合）に変化を見逃す。実際に使い捨てのポータルで再現した。

### 手順

- [x] 1. `PortalServer.removeVanishedProjects` を追加し、`scanWorksDirCore` から呼ぶ
- [x] 2. `closeSearcher` を追加し、searcher を閉じてから actor を止める
- [x] 3. `scanWorksDirCore` の戻り値を `{total, added, removed}` にし、`/api/scan-works-dir` の
      JSON と MCP の結果文に `removed` を足す
- [x] 4. `defaultSearcher` をフィールドにして、`McpHandler` へ `Supplier` で渡す
- [x] 5. `BuildJob` に `listChanged` を足し、JSON に出す。JavaScript の再読み込み判定を差し替える
- [x] 6. `ModeTest` に、改名したプロジェクトが一覧から消えることを確認するユニットテストを足す
- [x] 7. `rm -rf target && mvn install`（283件 GREEN）
- [x] 8. 使い捨てのポータルで、起動後にプロジェクトを改名してからボタンを押し、一覧が
      `[proj-a, proj-b]` から `[proj-a, proj-b-renamed]` へ描き直ることを確認
- [x] 9. `HtmlSaurusApi_260802_oo01` と `HtmlSaurusMcp_260803_oo01` に `removed` と `listChanged` を追記

## Review

### 画面

ヘッダ右端にあった `Scan Works Dir` と `Reindex All` は無くなり、左の開閉ペインの `Projects`
見出しの直下に `Update All Projects` が1つだけ並ぶ。押すと `Updating...` になり、右隣に
`(2/12) doc_SCIVICS002 html 84s...` の形で、処理中のプロジェクト名・段階・経過秒が出る。
完了すると `12 project(s) updated (1804s)` を緑で表示し、ボタンが元のラベルへ戻る。

再走査が新しいプロジェクトを登録した場合だけ、完了の1.5秒後にページを再読み込みする。
判定は、完了メッセージ先頭の件数と、描画済みの `.project-row` の個数を突き合わせて行う。
更新しただけの回でページを再読み込みしないのは、右ペインが表示している文書をそのまま残すためである。

### サーバ

`POST /api/update-all-projects-async` が `updateAllProjectsCore` をデーモンスレッドで開始し、
202 とジョブidを返す。進捗は既存の `GET /api/build-status?jobId=` がそのまま返す——
`BuildJob` の `project` に `(all projects)`、`stage` に `update-all-projects` を入れ、
`message` を各段階で書き換えるだけで、状態を返す側は1行も変えていない。

MCP ツール `update-all-projects` は同じ core を `BuildJob` 無しで呼ぶ。MCP の呼び出し側は
応答を待つので、進捗を問い合わせる相手がいない。

### 検証

ユニットテストは282件すべて GREEN。`ModeTest` の
`devPortalPage_hasThemeAndScanWorksDir` は、確認対象が消えたため
`devPortalPage_hasThemeAndUpdateAllProjects` に置き換え、新しいボタンが在ること、
旧2ボタンが無いことを見るようにした（ユーザーの許可を得て変更）。

使い捨てのポータル（`/tmp/hs-verify` に見本プロジェクト2個、ポート 28099）で実際に押して確認した。

- ボタンは `#portal-sidebar` の中にあり、`header` の中には無い
- `#scan-works-dir-btn`・`#reindex-all-btn` はページのどこにも無い
- 押すと `2 project(s) updated` を緑で表示し、JavaScript のエラーは出ない
- サーバのログに `scanning /tmp/hs-verify` → `(1/2) proj-a html` → `(2/2) proj-b html`
  → `(1/2) proj-a index` → `(2/2) proj-b index` → `embedding (2 projects)` の順で出た
- 起動後に `proj-c` を置いてから押すと、再走査が登録し、ページが再読み込みされて行が2から3に増えた

稼働中のポータル（28001）への配備と、そこでの実行は行っていない。

### 追記分の検証

ユニットテストを1件足して283件 GREEN。新しいテストは、プロジェクト2個でポータルを起動したあと
一方のディレクトリを改名し、`POST /api/scan-works-dir` が `"added":1,"removed":1,"total":2` を返すこと、
ポータルの画面から古い名前が消えて新しい名前が出ること、改名元のディレクトリが作り直されていないことを
確認する。

使い捨てのポータルでも同じ筋を実機で確認した。ボタンを押すと状態表示が
`2 project(s) updated (1 added, 1 removed)` になり、一覧が `[proj-a, proj-b]` から
`[proj-a, proj-b-renamed]` へ描き直った。追加も削除も無い回は再読み込みが起きず、表示中の文書が
そのまま残ることも確認した。

## リンク切れ検査（LinkChecker）

### 仕様

- 検査対象は `static-html/` に書き出された全 `.html`（ロケール別の `en/` なども含む）。
- 内部リンク（`<a href>`・`<img src>`・`<link href>`・`<script src>` のうちサイト内を指すもの）は、
  出力したファイルのパス一覧と突き合わせる。HTTP は使わない。
  - `href` はそのページ自身の `/` で終わる URL を基準に解決する（v2.5.0 のリダイレクト仕様と同じ基準）。
  - `../` を畳み、`?query` を除き、`%XX` を復号する。
  - 存在判定は `<パス>` のファイル、または `<パス>/index.html`。
  - 完全一致しなければ小文字に揃えて再照合し、一致すれば「大文字小文字の食い違い」、しなければ「存在しない」。
  - `#fragment` はリンク先ページの `id`・`name` に存在するかを見る。
- 外部リンク（`http(s)://`）は `--check-external` を付けた時だけ検査する。通常ビルドは外部サイトの状態に依存させない。
  - 重複を除き、`HEAD`、`405` 等なら `GET`。リダイレクトは辿る。同一ホストへは3秒以上の間隔、タイムアウトあり。
  - `404`・`410` は切れ。`403`・`429`・`5xx`・タイムアウト・DNS 失敗は「確認できない」（警告のみ）。`301`・`308` は移転済み（新 URL を報告）。
- 報告は「ページ URL・`href`・種別」。Markdown の行番号は HTML から引けないので出さない。

### 組み込み位置

- `html-saurus-lib` に `LinkChecker` を追加（`BuildStages` と同じ層）。
- `BuildOnlyMain` に `links` コマンドを追加（`--check-external`、`--strict` を持つ）。`html` ステージの末尾で内部検査を呼ぶ。
- 終了コード: 違反があって `--strict` の時だけ 1。既定は警告表示のみ（既存約570ページの違反を先に直すため）。

### 手順

- [x] 1. `LinkChecker` の内部リンク検査を `LinkCheckerTest` 先行で書く（赤→緑）。大文字小文字・`#fragment`・`index.html`・相対参照を含める
- [x] 2. `html` ステージ末尾と `links` コマンドへ接続する
- [ ] 3. 外部リンク検査（`--check-external`）を追加する。HTTP は注入可能にして単体テストはローカルのスタブサーバで行う
- [ ] 4. `nigsc_homepage2` の実サイトで実行し、検出された違反を報告する（TypeScript/typescript の件を含む）
- [ ] 5. `mvn install`（`rm -rf target` の後、`-DskipTests` なし）で全テスト GREEN を確認する

### 決定

- 既定は警告のみ、`--strict` で失敗（ユーザー承認済み）。外部リンク検査は `--check-external` で明示実行（ユーザー承認済み）。

### 経過

- 手順1・2: `LinkCheckerTest` 11件と lib 全体 179件が GREEN。`nigsc_homepage2` の `static-html/` に `links` を実行して 61件を検出した（内訳は報告参照）。
- 手順3（外部リンク）は未着手。

## リンク切れ検査 続き（ポータルモードを含める）

### 現状（実測）

- `BuildStages.build` は本番・ビルド専用・ポータルの全経路が通る。末尾で `reportBrokenLinks(outDir, 20)` を呼ぶので、ポータルの再ビルドでも標準エラー出力に警告が出る。ただしポータルの画面からは見えない。
- ポータルが作った既存の `static-html/` に `links` を実行した（読み取りのみ）。`doc_Base010` は 1 件、`doc_Infra002` は 11 件。11 件は `../017_Xxx/017_Xxx.md` のような番号付きの相対リンクや `.md` 付きのリンクで、URL としては存在しない。文書の規約（内部リンクは文書IDで書く）に反する書き方でもある。
- `<a href>` ではない参照が検査の外にある。`## 参考資料` の `data-doc-id` は、ほかの `doc_*` リポジトリにも無い ID だと、`ReferenceLabels` が「書いたまま残す」ので、切れていても誰も気づかない。

### 手順

- [x] 1. `data-doc-id` が全 `doc_*` のどこにも無い参照を `LinkChecker` に加える（種別 `UNKNOWN_DOC_ID`）。`ReferenceLabels` の ID 収集を共有する
- [x] 2. ポータルの画面からの見え方。`GET /api/link-check?project=<名>` が検出結果を JSON で返す。画面に何を足すかはユーザーが決める（ポータルの画面仕様は触らない）
- [ ] 3. 外部リンクの検査（`--check-external`）。同一ホストへ 3 秒以上の間隔、`HEAD`→`GET`、404/410 のみ「切れ」、他は「確認できない」
- [x] 4. 複数プロジェクトを同時に出すポータルで、プロジェクトをまたぐ絶対リンクが誤検出にならないか実測する
- [ ] 5. 検出済みの既存の違反（`doc_Infra002` の 11 件など）の一覧をユーザーに渡す。修正は文書の持ち主が行う

### 注意

- `PortalServer.java` と `tasks/todo.md` には、ほかの作業の未コミットの変更がある。手順 2 では最小の追加に留め、コミットは自分の変更だけを分けて行う。

### 経過（手順 1・2・4）

- `LinkChecker` に `UNKNOWN_DOC_ID`（`data-doc-id` が全 `doc_*` に無い）と `UNTRANSLATED`（`/en/` などのページが、既定ロケールにしか無い文書を指す）を追加した。HTML の文字参照（`&#x2013;`、`&nbsp;` など）を復号するようにした。lib 183→185件、portal-server 187件、いずれも GREEN。
- ポータルに `GET /api/link-check?project=<名>` を追加した（`LinkCheckApiTest` 3件）。画面への表示は未着手（画面仕様はユーザーが決める）。
- 手順 4: 全 `doc_*` の既存出力を実測したところ、別プロジェクトを指す絶対リンクの誤検出は 0 件。
- 実測（読み取りのみ・20 リポジトリ）: `MISSING` 6410、`UNTRANSLATED` 6247、`MISSING_FRAGMENT` 117、`UNKNOWN_DOC_ID` 13。
- 手順 3（外部リンク）と手順 5（違反一覧の引き渡し）が残り。

### 経過（リンク切れ時の3つの動き）

- ユーザー指定: リンク切れがあったら「ビルドしない」「ログに警告」「無視」の3種類。オプションは2つ。
- 実装: 既定は警告（`WARN`）。`--strict-links` で失敗（`FAIL`: 全件を表示し、終了コード 1、あとの段階は走らない）。`--ignore-links` で検査しない（`IGNORE`）。両方指定は拒否。`LinkPolicy` に3つの動きとオプション定義をまとめ、`build-only` の `html`・`all` と `production-server` の `serve` が使う。
- `BuildStages.build` は成否を `boolean` で返す。`serve` は失敗なら起動せず終了する（配備のとき、動いている Pod を置き換える前に止まる）。
- 検証: `LinkPolicyTest` 8件を追加。lib 193・production-server 18・portal-server 187・build-only 4、すべて GREEN。実コマンドで、既定は警告＋終了コード 0、`--strict-links` は終了コード 1 で索引を作らない、`--ignore-links` は何も言わない、両方指定は拒否、を確認した。
- ポータル（画面からのビルド）への適用は未着手。

### 経過（文書IDの検査・別プログラム）

- ユーザー指定: 本文に書かれた文書IDに該当する文書が無いものを警告する、別の仕組み。起動中のポータルに問い合わせて答えが返るかで判定する。
- ポータルの `GET /api/resolve?id=` が、あれば 200、無ければ 404 を返す（`localhost:28001` で実測）。
- 実装: `build-only` の `doc-ids` コマンド（`-d`、`-a`、`--portal`、`--strict`）。ビルドの段階ではなく独立したコマンドにした（ビルドがポータルの稼働に依存しないように）。`DocIdScanner`（ID の抽出。front matter と fenced code block は読まない）、`DocIdChecker`（読む・集計する）、`PortalResolver`（問い合わせる。接続できないのは「無い」ではなく失敗として扱い、終了コード 2）。
- 検証: 新規 15件（`DocIdScannerTest` 8、`DocIdCheckerTest` 7）。lib 208・build-only 4、GREEN。実ポータルで全 27 プロジェクトを実行し、1417 個の ID のうち 85 個が文書に対応しなかった。4 個はディスクで宣言の有無を直接確かめ、実在しないことを確認した。
- 既知の限界: 例として書かれた ID（`NoSuchDocument_000000_oo01` など）も報告される。ポータルが索引を更新する前の新しい ID も報告される。

### 経過（外部リンクの検査・別プログラム／自サイトの完全な URL）

- ユーザー指定: 外部リンクの検査は時間がかかるので別プログラムにする。並列は POJO-actor を一貫して使う。
- 実装: `build-only` の `external-links` コマンド（`-d`、`-a`、`--interval`（既定 3 秒）、`--strict`）。ホストごとに 1 つのアクター `HostWorker`（FIFO で 1 度に 1 リクエスト、リクエスト開始の間隔を空ける）、ホスト同士は並列。`LinkProbe` が HEAD→GET、リダイレクトを 1 段ずつ追う（301・308 は `MOVED`、404・410 は `GONE`、他は `UNSURE`）。素の POJO を先に書き、アクター化は `ExternalLinkChecker` だけ。
- 実測（`nigsc_homepage2`、458 件の外部アドレス・142 ホスト）: 2 分 14 秒。27 gone、73 moved、51 unsure、307 ok。
- 発見: 自サイトの完全な URL（`https://sc.ddbj.nig.ac.jp/...`）は内部リンクなのに、検査から漏れていた。`LinkChecker` が `url:` で名指しされた自サイトの URL を内部リンクとして扱うようにし、`external-links` からは外した。
- 修正: `ConfigReader.readSiteUrl` が `//url: 'http://localhost'` のような行頭コメントを先に拾っていた。コメント行を読み飛ばすようにした。
- 検証: lib 229・portal-server 187・production-server 18・build-only 4、すべて GREEN。

## PDF import: split into chapter subdirectories (local LLM decides the boundaries)

Goal: an option on the Import tab (PDF panel) that stores the imported book as `Chap01`, `Chap02`, ...
(two digits), with the chapter boundaries decided by the local LLM. Reference layouts:
`doc_Base011/docs/Books-Infra/MasteringK8s4thEd`, `.../LinuxKernelNetworking`,
`Books-Programming/CoreJava14th_Vol1` (front matter keeps its original name, appendices are `Appendix_A`...,
page numbers restart at `p001` inside each chapter).

Facts found (PortalServer.java / PdfImportJob.java / TranslationClient.java):
- `PdfImportJob` already keeps every page's Markdown in `pageMarkdown[]` and writes a batch every `pagesPerFile` pages.
- `TranslationClient` is the precedent for calling the local LLM (GPU broker `/v1/chat/completions`,
  model from AI workspace settings, else `TRANSLATION_MODEL`, else the default).
- No code creates `ChapNN` today; the import form is `#tab-import` (PortalServer.java ~1783-1820),
  posted by `startPdfImport()` to `/api/import/pdf/start`.

Plan:
- [x] 1. UI: checkbox `#import-pdf-chapters` in the PDF panel; send `splitChapters`; keep it in `localStorage`.
- [x] 2. `handleImportPdfStart` / `submitPdfImport`: pass `splitChapters` to `PdfImportJob`.
- [x] 3. `PdfImportJob`: when the option is on, OCR all pages first and defer the batch writing.
- [x] 4. New `ChapterBoundaryClient`: send "page number: first ~200 characters / headings" tables (windows of
        about 100 pages) to the local LLM and parse a JSON list `[{kind: front|chapter|appendix|back, title, startPage}]`.
- [x] 5. Code-side validation (start pages ascending, inside the page range, first chapter exists);
        on failure fall back to the current flat layout and put the reason in the job result.
- [x] 6. Writer: front matter in a directory with the book's own name, `Chap%02d`, `Appendix_<letter>`;
        batches inside a chapter are `<chapter title>_pNNN-MMM` with the page number restarting at 1.
- [x] 7. Progress stages shown in the Batch Job tab: OCR n/N, judging chapters, writing.
- [x] 8. Unit tests (no external service): JSON parsing, validation, directory naming, batch layout.
        E2E: add a scenario to `ImportTabE2E`.
- [x] 9. Build (`rm -rf target && mvn install`), run the portal, import a small PDF with the option, look at the result.

Out of scope for now: resuming a stopped import. (The bulk PDF panel was added afterwards, see the review.)

Review (chapter split):
- New: `ChapterSplitter` (pure: digest, answer parsing, validation, layout), `ChapterBoundaryClient` (asks the model
  100 pages at a time, retries an unreadable answer once). Changed: `PdfImportJob` (optional planner; OCR first, write
  by chapter, flat layout as the fallback), `TranslationClient.chat`, the PDF panel and `/api/import/pdf/start`
  (`splitChapters`).
- Tests: `ChapterSplitterTest` (12), `ChapterImportTest` (8, includes the job writing real files against stubs),
  `ImportTabE2E` T-5 and T-6 (checkbox default, persistence, request body). Full `mvn install`: lib 231, build-only 4,
  production-server 18, portal-server 207, all green.
- Checked by hand on a throwaway portal (port 8601, text-layer backend, the real model through the GPU broker): a 9-page
  LaTeX book became `book/`, `Chap01`-`Chap03`, `Appendix_A`, with page numbers restarting at p001 in each.
- Not done: an import that is stopped cannot resume; front/back matter the model names is written
  under its own title (index and acknowledgments are kept, not dropped).
- Bulk PDF panel (added afterwards): the same checkbox (`#import-pdf-bulk-chapters`, sent as `splitChapters`). Each PDF is a
  book of its own, so with the option on it is written under `<destPath>/<PDF name>/` (`ChapterSplitter.bookDirectoryName`);
  otherwise every book's `Chap01` would land in the same directory. Checked by hand with two PDFs on a throwaway portal
  (`Book One/Chap01..03`, `Book Two/Chap01..02`), and by `ImportTabE2E` T-7. Full `mvn install`: portal-server 208 tests, green.
