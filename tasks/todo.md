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
