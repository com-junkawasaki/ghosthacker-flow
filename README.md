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
（`test/` にテストあり、31 assertions）。レンダリング・入力・音声の各ホスト
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

`ghosthacker` 本体の `ghosthacker.resources`(pure) /
`ghosthacker.import`(JVM host adapter) と同型のレイヤ分離方針を踏襲している。

## 開発

```bash
clojure -M:test
```
