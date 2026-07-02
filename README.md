# GHOST HACKER: FLOW

Ghost Hacker ゲームポートフォリオ第1弾。設計は
[ADR-2607023200](../../../90-docs/adr/2607023200-ghosthacker-game-portfolio-flow.md)
（superproject `com-junkawasaki/root`）を参照。

[Ghost Hacker](https://github.com/com-junkawasaki/ghosthacker)（既存カノン: Ren/Nei、
「情報は物理だ」、情報場、Ghost Battle / Daemon Battle）を土台に、FreeTEMPO
（半沢武志、2000年代、ボサノバ/AOR/ジャズ/ハウス）のグルーヴ感を軸にした
10ジャンル展開の最初の1本。

## コンセプト

- **ジャンル**: アクション（疾走フロー）
- **主人公**: Ren単独
- **コアループ**: 情報場をボードで滑走し、四つ打ちのビートグリッドに合わせて
  ログの粒子（証拠の欠片）を拾う。入力タイミングのズレが `:perfect` /
  `:good` / `:miss` に判定され、`:groove`（0.0=TENSE 〜 1.0=Sky High）という
  楽曲crossfadeパラメータを動かす。乗れているほど曲がTENSEからSky Highへ
  転調していく — 「情報は物理だ」というテーマを演出でなく操作の結果として
  体現する。

## 現在の実装範囲

`src/ghosthacker_flow/core.cljc` に、以下の **pure ロジックのみ** を実装済み
（`test/` にテストあり、89 assertions）。レンダリング・入力・音声の各ホスト
アダプタ（tech stack未確定。`kotoba-lang/kami-engine-sdk` 流用が候補）は
未実装。

- ビート位相計算・タイミング判定（`beat-phase-ms` / `judge`）
- combo / `:groove`（TENSE⇄Sky High crossfadeパラメータ）状態遷移
  （`apply-judgment` / `judge-input`）
- comboに応じて頭打ちで伸びるscore倍率（`combo-multiplier`。乗り続けるほど
  得点効率が上がり、乗り続けること自体にscore面の動機づけを作る）
- accuracy / grade（`:sky-high` `:a` `:b` `:c` `:d`。accuracyとgrooveの両方が
  高い時だけ最高評価 `:sky-high` になる）
- ホストアダプタのリザルト画面にそのまま渡せる `summary`
- `beat-schedule`（譜面オーサリング/テスト用の理想入力列づくり）と、
  入力列をまとめて評価する統合API `judge-sequence`
- `judge-input-once` / `judge-sequence-once` — 同じ拍(`beat-index`)への
  二重入力（連打でのスコア稼ぎ）を検出し、タイミングに関わらず`:miss`
  扱いにする対マッシュガード
- `judge-detailed` — `:early` / `:late` / `:exact` を添えたタイミング判定
  （「はやい!/おそい!」表示向け）
- `play` — 入力列1本を対マッシュガードつきで評価してsummaryだけ返す最短経路
  （ただし『何拍流れるはずだったか』は知らないため、入力ゼロ=未プレイ扱いで
  accuracy 1.0 になる）
- `judge-run` / `play-run` — 期待される拍数(`beat-count`)と実際の入力を
  突き合わせ、入力が一度も届かなかった拍を明示的に`:miss`として積み増す。
  「押さなければmissにすらならない」を防ぐ、`play`の欠けを埋めるバリアント
- `difficulty-presets`（`:easy`/`:normal`/`:hard`）と `judge-input-difficulty`
  — 難易度ごとに判定窓(perfect/good)だけを差し替える。score/combo/groove
  の計算式自体は難易度に依らず共通

変更履歴は [CHANGELOG.md](CHANGELOG.md)。

`ghosthacker` 本体の `ghosthacker.resources`(pure) /
`ghosthacker.import`(JVM host adapter) と同型のレイヤ分離方針を踏襲している。

## 開発

```bash
clojure -M:test
```

`src/ghosthacker_flow/demo.clj`（JVM専用、host adapterではない）で、
上記APIを一通り動かして結果を標準出力に表示できる。`:groove`
（TENSE⇄Sky High crossfadeパラメータ）の推移はASCIIバーの1拍1行タイムラインで
可視化される:

```bash
clojure -M -m ghosthacker-flow.demo
```
