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
