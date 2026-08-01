# html-saurus: 意味版（embedding）関連文書 — todo

## 目的
ドキュメントサイトの各ページに「意味（セマンティック）で関連する文書」の一覧を出す。
既存の TF-IDF 版（Lucene MoreLikeThis）とは独立した第2の機能として追加する。
埋め込みは内蔵せず、ビルド時に共有埋め込みサーバ `http://192.168.5.17:8012/embed`
（multilingual-e5-large, GPU, 1024次元）を HTTP で叩く。粒度は chunk→文書pool。

## 計画（直列・フォアグラウンド。並列/バックグラウンドは使わない）

- [x] 0. 足場確認: exdb2 `EmbeddingClient`、html-saurus の
      `SearchIndexer`/`Main`/`ConfigReader`/`PortalServer`、Lucene スキーマ、
      `injectRelatedDocs`/`/api/related` の配線を読了
- [x] 1. `EmbeddingClient.java` 追加（薄い HTTP クライアント、Jackson不使用＝McpJsonParser）。
      URL は env `EMBEDDING_SERVER_URL`・既定 5.17:8012。到達不可は loud error
- [x] 2. 各文書を ~512トークン(=1000字) chunk 分割→batch 埋め込み→mean-pool→L2正規化
- [x] 3. `SemanticIndexer` がビルド済み Lucene 索引から path/title/body を読み、
      ロケール単位で top-K(=20) 近傍を cosine 事前計算 → `worksDir/.semantic-related.json`
- [x] 4. `/api/related-semantic` + `/related-semantic` + 2枚目ウィジェット
      「Related (semantic)」注入。TF-IDF 版は無改変（描画は共有ヘルパに抽出）
- [x] 5. 検証: ユニット10件 GREEN、`mvn install` BUILD SUCCESS、
      ライブ検証（doc_DDBJ-dev001 6文書→5.17→妥当な意味近傍）成功

## 決定事項
- 粒度 = chunk→文書pool
- 埋め込み URL はハードコードせず env `EMBEDDING_SERVER_URL`（既定 5.17:8012）
- バージョン番号・デプロイはビルド段階でユーザーに確認する

## 追加リファクタ（per-project 対称化、2026-06-17）

- [x] R1. `SemanticIndexer` 段A化: `doc_X/search-embedding/vectors.bin` に per-project
      ベクトル（素のバイナリ）を書く。`vectors.bin` 無い or `search-index/` より古い時だけ再埋め込み
- [x] R2. `SemanticIndex`(新規) 段B化: 全 `vectors.bin` を読み、起動時にメモリで近傍計算（並列）。
      グローバル `.semantic-related.json` は廃止
- [x] R3. `PortalServer`: `.json` ロード廃止→`SemanticIndex.servedMap((proj,p)->"/"+proj+p)`
- [x] R4. `SearchServer`(single): `/api/related-semantic`・`/related-semantic`・ウィジェット注入を新規配線
      （urlFn は (proj,p)->p）
- [x] R5. `Main`: `buildSemanticIndexIfAbsent` 廃止→`ensureSemanticVectors`(per-project staleness)
      ＋`SemanticIndex.load` を runSingle/runPortal 両方に配線
- [x] R6. `RelatedDocsView`(新規) に JSON出力・関連ページ・緑ウィジェットを共有化（portal/single 重複排除）。
      binary IO 往復テスト追加。121テスト GREEN、ライブ検証（段A 6.5s→再利用 2ms、段B 近傍妥当）

## クエリ意味検索（RAG様 query→doc、2026-06-21）

- [x] Q1. `SemanticIndex.search(queryVec, topN, urlFn)`: メモリ上の全文書ベクトルと cosine で top-N
      （ロケール跨ぎ＝多言語 e5 を活用）
- [x] Q2. `RelatedDocsView.searchResultsPage(query, hits)`: 検索ボックス付き SSR。ページ chrome を
      `pageOpen`/`appendResults` に共通化（related ページと共有）
- [x] Q3. portal/single 両方に `/api/search-semantic?q=`（JSON）＋`/search-semantic?q=`（SSR）。
      各 server が `EmbeddingClient`（env `EMBEDDING_SERVER_URL`）を持ち、リクエスト時にクエリを1回埋め込み
- [x] Q4. プレフィックスは verbatim（環境規約・OpenWebUIと同条件・文書ベクトル再利用）
- [x] Q5. `SemanticIndexTest`(3) 追加。124テスト GREEN。ライブ検証で日本語クエリ→正しい文書が1位
      （「大きなファイルのアップロード」→ファイルストレージ設計、「HPCクラスタ」→OpenHPC 等）

### リファクタ後の確認事項
- 陳腐化は per-project: `search-index/` を作り直して再起動すると該当プロジェクトだけ再埋め込み。
- グローバル `.semantic-related.json` は無くなった（古いファイルが嘘をつく問題が消えた）。
- single モードにも「Related (semantic)」ウィジェット＋エンドポイントが付いた（退行なし、TF-IDF は portal のみのまま）。
- バージョンは 2.1.0 のまま上書き（未起動のため）。デプロイ済み、再起動はユーザー。

## レビュー（実装後）

### 実装したもの
- `EmbeddingClient.java`（新規）: OpenAI互換 `/v1/embeddings` を叩く。ベクトルは
  クライアント側で L2 正規化。テキストはそのまま送る（OpenWebUI と同条件）。
- `SemanticIndexer.java`（新規）: ビルド時バッチ。ビルド済み Lucene 索引を読み、
  chunk→pool で1文書1ベクトル、ロケール単位 top-K 近傍を `.semantic-related.json` へ。
- `PortalServer.java`: `.semantic-related.json` を起動時ロード、`/api/related-semantic`・
  `/related-semantic`・緑の「Related (semantic)」ウィジェット追加。JSON出力とページ描画は
  既存 TF-IDF と共有ヘルパ（`writeRelatedJson`/`renderRelatedPage`）に抽出。
- `Main.java`: 配信前に `buildSemanticIndexIfAbsent` を呼ぶ。
- ユニットテスト: `EmbeddingClientTest`(5)・`SemanticIndexerTest`(5)。

### 当初計画からの逸脱（要ユーザー確認）
1. **実サーバは OpenAI 互換 `/v1/embeddings`** だった（`/embed` ではない＝404）。
   ライブ検証で判明し `EmbeddingClient` を修正。メモリも訂正済み。
2. **「到達不可ならビルドを失敗」を緩和**: 埋め込みサーバが落ちていても
   ポータル全体は配信し、意味版ウィジェットだけ空にする（loud error をログ）。
   TF-IDF 版は無影響。理由: 1サーバの停止で全ドキュメントサイトを落とすのは過剰。
3. **再ビルド条件**: `.semantic-related.json` が存在すれば再利用（毎起動の再埋め込み回避）。
   全文書の再計算は同ファイル削除でトリガ。オンデマンド再ビルドは将来課題。

### 未了（デプロイ前にユーザー判断）
- 本番デプロイ（jar 差し替え）とバージョン番号はユーザーが決める。
- 全 4,019 文書での初回 `.semantic-related.json` 生成は未実行（小規模検証のみ）。
  実運用ホストでポータル起動時に生成される。

## 段落単位オンデマンド翻訳（アコーディオン挿入）

### 目的
本文を読みながら、必要な段落だけをその場で対訳表示できるようにする。
常時両言語併記ではなく、ボタンを押した時だけ翻訳を取得・挿入する（オンデマンド）。
参考実装: `quarkus-english-drill` の KWIC 翻訳（段落単位で並列 fetch → 個別に DOM 挿入、
サーバ側でハッシュキャッシュ→ヒットなしのみ LLM 呼び出し）。

### 計画（直列・フォアグラウンド。並列/バックグラウンドは使わない）

- [x] 1. `TranslationClient.java` 追加。`EmbeddingClient.java` と同じ流儀
      （`HttpClient` + `HTTP_1_1` 固定、`McpJsonParser`、Jackson不使用）。
      既定 URL `http://192.168.5.17:8000/v1/chat/completions`（Gemma-4, env で上書き可）。
      入力: 段落テキスト＋目的言語。出力: 翻訳文字列、失敗時は `null`。
- [x] 2. ファイルベースのキャッシュ（DB は使わない。html-saurus に既存の DB 層がないため、
      `SemanticIndexer` のベクトルキャッシュと同じ思想でプロジェクトごとの平ファイルにする）。
      キー = SHA-256(段落テキスト + 目的言語)。
- [x] 3. `SearchServer.java`（および `PortalServer.java`、当初計画外だが両モードとも
      `PageRenderer`/`PageScripts` を共有するため必須と判明。ポータル側キャッシュは
      プロジェクトを跨いで共有、`worksDir` 直下に1つ） に `POST /api/translate` を追加。
      キャッシュ確認→ヒットなしのみ `TranslationClient` 呼び出し→キャッシュへ保存。
- [x] 4. `PageRenderer.java` の既存 `if (!production)` ブロック（270行目付近、
      Text/Markdown/Path ボタンと同じ `.copy-bar`）に4つ目のボタン `#translate-btn` を追加。
      目的言語はページの `currentLocale`/`defaultLocale` から算出（日本語ページ→英語、
      英語ページ→日本語）し `data-target-lang` に埋める。**production ビルドには一切出力しない**
      （既存ボタン群と同じ条件分岐に載せるだけで自動的に満たされる）。
- [x] 5. `PageScripts.java` に JS 追加。ボタン押下で対象要素を全走査し、KWIC と同じく
      要素ごとに独立した並列 `fetch('/api/translate', ...)`。各要素は返り次第、待たずに
      アコーディオン（`<details><summary>訳</summary>...</details>`、ブラウザ標準の開閉を
      利用しJS側の開閉ロジックは書かない）を挿入する。対象と挿入位置は要素の種類で分ける。
      - `p`, `h1`〜`h6`: `main` 直下を問わず全て対象。直後に兄弟要素として挿入。
      - `li`: ネストした `ul`/`ol` の文字は翻訳対象に含めない（自分の直接のテキストのみ）。
        アコーディオンはその `li` の最後の子として追加（兄弟 `li` にすると番号・箇条書きが
        ずれるため）。
      - `tr`: セルのテキストを ` | ` 区切りで結合して1単位として翻訳（タブから変更、
        LLM への可読性のため）。直後に、列数ぶんの `colspan` を持つ `<td>` 1個だけの
        新しい `<tr>` を挿入し、`data-translated` を立てて再走査で拾わないようにする。
- [x] 6. ユニットテスト: `TranslationClientTest`（`parseContent` のみ。不正 JSON ケースは
      `McpJsonParser` 自体の責務なので省略、`EmbeddingClientTest` の前例に合わせた）、
      `TranslationCacheTest`（キー安定性、get/put、ファイル再読み込みでの往復、上書きしない
      ことを確認）。
- [x] 7. `mvn install`（`-DskipTests` 禁止、事前に `rm -rf target`）。ビルド後、実ページで
      ボタン→段落アコーディオン挿入→productionビルドでボタン非表示、を目視確認。

### 決定事項
- 粒度は段落・見出し・リスト項目・表の行。挿入方法は種類ごとに異なる（上記5参照）。
- オンデマンド生成のみ。事前一括生成・常時併記はしない。
- production モードではボタンごと出力しない。
- JSON エスケープは新規追加せず、既存の `HttpUtils.jsonStr`（正規のユーティリティ）を再利用。
- 翻訳サーバ既定 URL は `http://192.168.5.17:8000`（env `TRANSLATION_SERVER_URL` で上書き可）、
  モデルは `google/gemma-4-26B-A4B-it`（env `TRANSLATION_MODEL` で上書き可）。

## レビュー（実装後）

### 実装したもの
- `TranslationClient.java`（新規）: `EmbeddingClient` と同じ流儀の薄い HTTP クライアント。
  OpenAI 互換 `/v1/chat/completions` を叩き、`choices[0].message.content` を返す。
- `TranslationCache.java`（新規）: SHA-256 キー、タブ区切り平ファイル、`synchronized` の
  get/put。書き込みは即座に追記（プロセス再起動でも失われない）。
- `SearchServer.java`・`PortalServer.java`: `POST /api/translate?lang=...`（本文は生テキスト）。
  両方とも `!production` の条件分岐に載せたため、エンドポイント自体が production では 404。
- `PageRenderer.java`: 既存 `.copy-bar` に4つ目のボタンを追加。目的言語はページのロケールから
  自動算出。
- `PageScripts.java`: ボタン押下で `p`/見出し/`li`/`tr` を並列 fetch、返り次第個別に挿入。
- テスト: `TranslationClientTest`(2)・`TranslationCacheTest`(5)。150テスト GREEN。

### 検証（使い捨てインスタンスで実施、本番 doc_* には触れていない）
- `src/test/fixtures/sample-site` を `/tmp` にコピーし、dev モードと `--production` モードを
  別ポートで起動して比較。
- dev モード: ボタン HTML が出力される。`POST /api/translate` を実際に叩き、
  `192.168.5.17:8000` の Gemma-4 から実翻訳が返ることを確認（0.65秒）。同じテキストを
  再送すると 0.009秒でキャッシュヒット、`translation-cache/cache.tsv` に1行追記されていた。
- production モード: `.copy-bar`/`translate-btn` とも HTML に出現せず、`/api/translate` は
  404（`createContext` 自体が登録されない）。
- 使い捨てサーバは検証後に PID を特定して kill、`/tmp` の作業ディレクトリも削除済み。

### 当初計画からの逸脱
1. **`PortalServer.java` も必須だった**: 当初計画は `SearchServer.java`（single-project
   モード）のみを想定していたが、`PageRenderer`/`PageScripts` は両モード共通なので、
   ポータルモードにもボタンが出る以上エンドポイントも要る。ポータル側は複数プロジェクトを
   跨ぐため、キャッシュをプロジェクト単位でなく `worksDir` 直下の1ファイルに変更した
   （翻訳はどのプロジェクト由来かに依存しないため、共有して問題ない）。
2. **表の区切り文字をタブから `|` に変更**: 計画時点ではタブ区切りとしていたが、LLM に渡す
   文字列としての可読性を優先して ` | ` に変更した。

### 未了（ユーザー判断）
- 見出し・リスト・表を含む実ドキュメントでの目視確認は sample-site 止まり。表を含む
  実際の doc_* ページでの確認はまだ。

## 追加: GPU不通時にサーキットブレーカーで試行そのものを止める

ユーザー要望: gemma→claude のフォールバックチェーンではなく、GPU に繋がらないなら
翻訳を試みようとしないでほしい、というもの。english-drill のフォールバック移植は却下。

### 実装したもの
- `TranslationClient` に `downUntilMs`（`AtomicLong`）を追加。`translate()` は失敗直後
  `markDown()` を呼び、以後30秒間は `isDown()` が true を返す間、接続を一切試みず
  即座に `null` を返す。成功時は `downUntilMs` を0に戻す。
- `TranslationClientTest` に3件追加（fresh は up、`markDown()` 直後は down、
  30秒経過後は up に戻る）。153テスト GREEN。

### 検証
- `TRANSLATION_SERVER_URL` を到達不能なアドレスに向けた使い捨てインスタンスで確認。
  1回目 0.036秒（実際に接続試行→502）、2回目 0.008秒・3回目 0.007秒
  （サーキットブレーカーが効いて接続を試みず即502）。使い捨てサーバは検証後に停止・削除済み。

### 未了
- commit・push はこの直後に実施（デプロイはユーザー側で対応）。

## 追加: UI微調整（クリックヒント削除・全部開閉ボタン・フォント縮小）

ユーザーが実機で動作確認した上でのフィードバック3点。

### 実装したもの
- `page.css`: `main details.inline-translation > summary::after { display: none; }` で
  既存の「クリックで展開/折りたたみ」ヒント（サイト全体の`<details>`向け、6月以前からの
  既存機能）を翻訳アコーディオンだけ無効化。他の`<details>`（admonition等）は無改変。
  ヒント用に確保されていた右余白（10rem）も、翻訳アコーディオンだけ2.2remに縮小。
- `page.css`: `main details.inline-translation` とその `summary` のフォントサイズを
  それぞれ0.85rem・0.82remに縮小（既定は本文相当・0.92rem）。
- `PageRenderer.java`: `.copy-bar`にTranslateの隣へ「Expand all」「Collapse all」
  ボタンを追加（`production`では非表示、既存ボタン群と同条件）。
- `PageScripts.java`: 2ボタンの押下で `main details.inline-translation` を
  全て`open = true/false`に一括変更。

### 検証
- 153テスト GREEN、`mvn install` 成功。
- 使い捨てインスタンスで新規4ボタン（Text/Markdown/Path/Translate/Expand all/Collapse all）
  がcopy-barに正しい順で出力されることを確認、CSSも出力HTMLに含まれることを確認。
  検証後に停止・削除済み。

### 未了
- commit・push はこの直後に実施。
