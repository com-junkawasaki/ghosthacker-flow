# Changelog

pure `.cljc` groove-sync core（`ghosthacker-flow.core`）と、それを使う
プロトタイプ実装の変更履歴（ADR-2607023200）。

## Unreleased

- `terminal.clj` を chart 判定に載せ替え。既定で前半TENSE(既定bpm)→後半
  Sky High(1.25倍速)へ加速する2セクションの曲(`default-chart`)を
  `core/chart-beats`で組み、`judge-input-once`単一bpm判定の代わりに
  `judge-chart-input`で判定する。プレイヤーが乗るほど転調していく
  groove-syncのテーマが、実際にプレイできるプロトタイプの曲構成として
  初めて体現された。
- `core.cljc` に chart（複数セクション/可変bpm）判定を追加。ここまでの
  `judge-*`/`beat-*`系は「run全体を通して単一bpm」前提だったため、
  TENSE(遅め/疎)→Sky High(速め/密)のようにセクションごとにbpmが変わる
  曲を1本の判定対象として扱えなかった。`chart-beats`で複数セクション
  （各`{:bpm :beat-count}`）を連結した「拍の絶対時刻msの昇順vector」を
  作り、`judge-chart-input`/`judge-chart-sequence`/`chart-run`/
  `chart-play-run`はそれを唯一の入力として判定する
  （`section-beats`/`section-duration-ms`/`nearest-chart-beat`が内部実装）。
- `terminal.clj` に「3, 2, 1, GO!」のカウントダウンを追加。最初の1拍の
  タイミングを合わせやすくする導入演出。

## aa3b121 → c248863

- `ghosthacker_flow.terminal` — 新規依存ゼロの、実際にEnterキーで遊べる
  最小ターミナルプロトタイプ。バックグラウンドの`future`が実時刻でtickを
  刻み、メインスレッドが`read-line`+実経過時間で判定する。
  - 実装中に見つけた実バグ: `future`はclojure.lang.Agentの非daemonスレッド
    プールを使うため、`shutdown-agents`を呼ばないとロジック完了後もJVM
    プロセスが終了せずハングする（15秒timeoutで実際に検証・再現・修正）。

## a0bc086 → aa3b121

- lint: `:lint`エイリアス（clj-kondoをClojars経由で取得、Homebrew等の
  システムインストール不要）をCIにも追加。現状 errors: 0, warnings: 0。

## 0696f04 → a0bc086

- `judge-detailed-with-windows` / `judge-detailed-difficulty` —
  `judge-input-difficulty`と対称になるよう`judge-detailed`系にも判定窓
  パラメータ版/難易度対応版を追加（非対称だった箇所の解消）。

## f522a3f → 51d288d

- コードレビュー: `beat-phase-ms`の負のmod分岐がdead codeだったのを除去
  （Clojureの`mod`はfloored divisionで、interval>0(常に保証)なら結果は
  常に非負——Javaの`%`のような負の余りにはならない）。挙動は不変（全テスト
  green）で、負のelapsedを明示的にテストするケースを追加した。

## 5aa4c34 → f522a3f

- CI（`.github/workflows/test.yml`）を追加。`main`へのpush/PRで
  `clojure -M:test` を自動実行。これまでテストは手動実行のみだった。

## 714b8da → 5aa4c34

- `beat-interval-ms` に `bpm<=0` の境界ガード（`{:pre [(pos? bpm)]}`）。
  唯一の入口で弾くことで、全bpm系関数がNaN/Infinityの無音伝播から守られる。

## 1858038 → 714b8da

- demo.clj に `:groove`（TENSE⇄Sky High）のASCIIタイムライン可視化を追加
  （1拍1行、comboと一緒にバー表示）。

## 4235476 → 1858038

- `demo.clj` / `demo-test.clj` — API一通りを標準出力で確認できるCLIデモと
  そのスモークテスト（`clojure -M -m ghosthacker-flow.demo`）。

## d96eef7 → 4235476

- `judge-with-windows` / `difficulty-presets`（`:easy`/`:normal`/`:hard`）/
  `judge-input-difficulty` — 判定窓を難易度ごとに差し替え可能にした
  （`judge`は`judge-with-windows`の`:normal`相当ラッパーへリファクタ、
  外部動作は不変）。

## 33f0d6a → d96eef7

- `judge-run` / `play-run` — 期待される拍数と実入力を突き合わせ、入力ゼロの
  拍を明示的に`:miss`として積み増す（`play`だと押さなければmissにすら
  ならない、という穴の修正）。

## 4552d9d → 33f0d6a

- `judge-detailed` — `:early` / `:late` / `:exact` を添えたタイミング判定。
- `play` — 入力列1本を対マッシュガードつきで評価してsummaryだけ返す最短経路。

## 36936c6 → 4552d9d

- `beat-index` / `judge-input-once` / `judge-sequence-once` — 同じ拍への
  二重入力（連打でのスコア稼ぎ）を検出する対マッシュガード。

## 77e5fa4 → 36936c6

- `beat-schedule` — 拍の絶対時刻列を生成（譜面オーサリング/テスト用の理想入力列づくり）。
- `judge-sequence` — 入力列をまとめて `judge-input` に畳み込む統合API。

## c978c7d → 77e5fa4

- `combo-multiplier` / score加算 — comboに応じて頭打ちで伸びる倍率。
- `accuracy` / `grade`（`:sky-high` `:a` `:b` `:c` `:d`）/ `summary`。

## c978c7d — initial

- `beat-phase-ms` / `judge` / `apply-judgment` / `judge-input`。
- `:groove`（TENSE⇄Sky High crossfadeパラメータ）の基本状態遷移。
