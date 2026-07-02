# Changelog

pure `.cljc` groove-sync core の変更履歴（ADR-2607023200）。レンダリング/
入力/音声ホストアダプタは未着手のため、ここは `ghosthacker-flow.core` の
API変遷の記録。

## Unreleased

- `beat-schedule` — 拍の絶対時刻列を生成（譜面オーサリング/テスト用の理想入力列づくり）。
- `judge-sequence` — 入力列をまとめて `judge-input` に畳み込む統合API。

## c978c7d → 77e5fa4

- `combo-multiplier` / score加算 — comboに応じて頭打ちで伸びる倍率。
- `accuracy` / `grade`（`:sky-high` `:a` `:b` `:c` `:d`）/ `summary`。

## c978c7d — initial

- `beat-phase-ms` / `judge` / `apply-judgment` / `judge-input`。
- `:groove`（TENSE⇄Sky High crossfadeパラメータ）の基本状態遷移。
