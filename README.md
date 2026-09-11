# GHOST HACKER: FLOW

![test](https://github.com/com-junkawasaki/ghosthacker-flow/actions/workflows/test.yml/badge.svg)

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

判定/score/combo/groove crossfadeのpure核（`beat-phase-ms`/`judge`/
`apply-judgment`/`chart-beats`等）は
[com-junkawasaki/ghosthacker-groove-core](https://github.com/com-junkawasaki/ghosthacker-groove-core)
に切り出した（[ADR-2607032600](../../../90-docs/adr/2607032600-ghosthacker-groove-core-extraction.md)。
HARMONY（ポートフォリオ#2、旗艦音ゲー）と共有するため）。API詳細はそちらの
README/docstringを参照。このリポジトリに残るのはFLOW固有のホストアダプタ:

**プレイ可能な最小プロトタイプ**として `src/ghosthacker_flow/terminal.kotoba`
がある。新規依存ゼロ（JVM標準の`future`/`read-line`/`System/currentTimeMillis`
のみ）で、バックグラウンドスレッドが実時刻でtickを刻みながら、メインスレッドが
`read-line`で入力を受けて実際の経過時間を判定する——グラフィック/音声は
無いが、実際に人がEnterキーを叩いて遊べる。既定の曲構成は前半TENSE→後半
Sky Highへ加速する2セクションのchart（`ghosthacker.groove.core/chart-beats`）
で、乗るほど転調していくgroove-syncのテーマを実際にプレイできる形で体現
している。

`src/ghosthacker_flow/demo.kotoba` — 入力/音声なしのCLIデモ。coreのAPI表面を
実行して確認するためのもの。

**ブラウザで遊べるホストアダプタ**が `src/ghosthacker_flow/web.kotoba`
（reagent、ADR-2607100900 follow-up (b)）: `kami-engine-sdk`調査の結果
（実体はwasm-bindgen Rustエンジンを包むSvelte/TS SDKで、cljsからも同じ
wasmモジュールを直接呼べる）を踏まえ、リアルタイム判定と音声は
ClojureScript側で先に成立させた。作曲済みの2レイヤー楽曲は存在しない
ため、Web Audioの`AudioContext.currentTime`（`performance.now()`より
高精度なスケジューリング）でビートクロックと合成メトロノーム音
（オシレーター、外部音声アセット不要）の両方を駆動しつつ、`:groove`は
音声crossfadeの代わりに見た目のTENSE(寒色)⇄Sky High(暖色)crossfadeを
駆動する。Web Audio非対応環境（このリポジトリ自身のheadless検証等）
では`performance.now()`+無音に自動degrade。

**実描画（キャンバス/WebGPU）**も同じ`web.cljs`に追加済み: CLAUDE.mdの
「app/gameの描画のために新規Rust crateを書かない」ルール（2026-07-10）を
踏まえ、`kami-engine-sdk`のwasm export（VRMキャラビューア専用で今回の
用途には不適）や`kami-app-animeka-timeline`型の新規Rust crateパターンは
採らず、**`kotoba-lang/webgpu`（宣言的WebGPU-from-EDN、Rust/wasm不要、
network-isekaiが実運用中の同じ執行系）**を`:local/root`依存として採用。
`:groove`で色付いた12個の「ログの粒子」（証拠の欠片）が情報場を漂う
シーンを`kami.webgpu.ir/render-ir`で組み立て、既存のreagent+Web Audio
ホストの背景として`#flow-canvas`に描画する。WebGPU非対応環境（jsdom、
旧ブラウザ）では`kami.webgpu/init!`のPromiseがrejectされ、DOM/CSSの
crossfadeだけで従来通り遊べる（無音/無キャンバスへの自動degrade、Web
Audioと同じ精神）。**この`webgpu`依存はwest管理下の兄弟パス
（`../../kotoba-lang/webgpu`）を前提とするため、west checkoutの外で
このリポジトリ単体をcloneしてもブラウザビルドだけは解決できない**
（network-isekaiの`kami-webgpu`利用も同じ制約を持つ、既存の許容トレード
オフ）——JVM側の`:test`/`:lint`はこの依存に一切触れないため、CIには
影響しない。
network-isekai（isekai.network/gftd/ghosthacker-flow）向けには、coreを
requireする代わりに`kotoba-lang/kami-engine`のゲスト言語サブセットへ
1から移植した`logic.cljc`が別途ある（該当リポジトリのpublic/games/gftd/
ghosthacker-flow/を参照）。

変更履歴は [CHANGELOG.md](CHANGELOG.md)。

`ghosthacker` 本体の `ghosthacker.resources`(pure) /
`ghosthacker.import`(JVM host adapter) と同型のレイヤ分離方針を踏襲している。

## 開発

```bash
kbb -M:test
```

Lint（clj-kondo、Clojars経由でHomebrew等の別インストール不要）:

```bash
kbb -M:lint
```

`main`へのpush/PRで `.github/workflows/test.yml` が自動でテスト+lintを実行する。

`src/ghosthacker_flow/demo.kotoba`（JVM専用、host adapterではない）で、
上記APIを一通り動かして結果を標準出力に表示できる。`:groove`
（TENSE⇄Sky High crossfadeパラメータ）の推移はASCIIバーの1拍1行タイムラインで
可視化される:

```bash
kbb -M -m ghosthacker-flow.demo
```

`src/ghosthacker_flow/terminal.kotoba` は実際にEnterキーで遊べる最小プロトタイプ
（引数で拍数を指定可、既定8拍）。「3, 2, 1, GO!」のカウントダウンで
最初の1拍のタイミングを合わせやすくしている:

```bash
kbb -M -m ghosthacker-flow.terminal        # 既定8拍
kbb -M -m ghosthacker-flow.terminal 16     # 16拍
```

ブラウザで遊んでみる（`npm install`は初回のみ、Spaceキーで入力）:

```bash
npm install
amu compile --target wasm32-browser app   # http://localhost:8292 で自動リロード開発
amu compile --target wasm32-browser app # public/ に静的バンドルをビルド(デプロイ可能)
```

## ライセンス

MIT License — [LICENSE](LICENSE) 参照。
