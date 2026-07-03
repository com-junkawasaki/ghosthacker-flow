(ns ghosthacker-flow.terminal-test
  "terminalの`-main`はshutdown-agentsを呼ぶため、共有JVMで動くテスト
   プロセス全体のagentスレッドプールを止めてしまう(以降のテストが
   futureを使えなくなる)。よって-mainそのものはテストせず、read-beats!
   （private var経由）を直接叩く。実プロセスとしての-main自体は
   手動検証済み(EOF/連打とも正しく完了しプロセスがハングしないことを確認)。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ghosthacker-flow.terminal :as terminal]))

(def ^:private read-beats! #'terminal/read-beats!)
(def ^:private countdown! #'terminal/countdown!)

(defn- silently [thunk]
  (binding [*out* (java.io.StringWriter.)]
    (thunk)))

(defn- capture-out [thunk]
  (let [w (java.io.StringWriter.)]
    (binding [*out* w] (thunk))
    (str w)))

(deftest read-beats-eof-test
  (testing "stdinがEOF(空)ならjudgmentゼロのまま即座に打ち切る(ハングしない)"
    (let [state (silently #(with-in-str "" (read-beats! 120 (System/currentTimeMillis) 4)))]
      (is (= [] (:judgments state)))
      (is (zero? (:score state))))))

(deftest read-beats-quick-input-test
  (testing "複数行を即座に読んでも例外にならない。最初の入力はperfect/goodのいずれか
            (JVM起動オーバーヘッドの誤差を許容)、以降は同じ拍への対マッシュガードで
            全てmissになる"
    (let [start (System/currentTimeMillis)
          state (silently #(with-in-str "\n\n\n\n" (read-beats! 120 start 4)))]
      (is (= 4 (count (:judgments state))))
      (is (not= :miss (first (:judgments state))))
      (is (every? #(= :miss %) (rest (:judgments state)))))))

(deftest read-beats-partial-input-test
  (testing "beat-count分に満たない入力(途中でEOF)は、そこまでのjudgmentsで打ち切る"
    (let [start (System/currentTimeMillis)
          state (silently #(with-in-str "\n\n" (read-beats! 120 start 5)))]
      (is (= 2 (count (:judgments state)))))))

(deftest countdown-test
  (testing "3, 2, 1, GO!の順で表示される（高bpmでsleepを短くしてテストを速くする）"
    (let [output (capture-out #(countdown! 6000))]
      (is (= ["3" "2" "1" "GO!"] (str/split-lines output))))))
