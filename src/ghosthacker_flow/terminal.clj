(ns ghosthacker-flow.terminal
  "GHOST HACKER: FLOW — minimal terminal host adapter (playable prototype).

  A real, human-playable loop built with nothing beyond JVM/Clojure already
  in this project: a background thread (`future`) sleeps to each beat's
  wall-clock time and prints a tick; the main thread blocks on `read-line`
  for each beat and judges the real elapsed time against
  `ghosthacker-flow.core`. No audio/rendering — just proof that the pure
  core can drive a genuinely timed, interactive loop end-to-end.

  Run: clojure -M -m ghosthacker-flow.terminal [beat-count]"
  (:require [ghosthacker-flow.core :as core]))

(defn- print-tick! []
  (print "♪ ")
  (flush))

(defn- run-ticker!
  "schedule(絶対ms時刻の列)どおりにtickを印字するfutureを起動する。
   呼び出し側はゲーム終了時にfuture-cancelで止めること。"
  [schedule]
  (future
    (doseq [t schedule]
      (let [wait (- t (System/currentTimeMillis))]
        (when (pos? wait)
          (Thread/sleep wait)))
      (print-tick!))))

(defn- read-beats!
  "beat-countぶんread-lineで入力を待ち、judge-input-onceで都度判定して
   進行状況を印字する。標準入力がEOF(nil)になったら、そこまでのstateで
   打ち切る（実プレイでCtrl-D、テストで空stdinを渡した時の両方に対応）。"
  [bpm start-time-ms beat-count]
  (loop [state core/initial-state i 0]
    (if (>= i beat-count)
      state
      (let [line (read-line)]
        (if (nil? line)
          state
          (let [now (System/currentTimeMillis)
                next-state (core/judge-input-once state bpm start-time-ms now)
                judgment (last (:judgments next-state))]
            (println (format " -> %s (combo %d)" (name judgment) (:combo next-state)))
            (recur next-state (inc i))))))))

(defn -main [& args]
  (let [bpm core/default-bpm
        beat-count (if-let [a (first args)] (Integer/parseInt a) 8)]
    (println (format "GHOST HACKER: FLOW — terminal prototype (bpm=%d, %d beats)" bpm beat-count))
    (println "Enterキーで各拍を叩いてください。準備ができたらEnterで開始:")
    (read-line)
    (let [start-time-ms (System/currentTimeMillis)
          schedule (core/beat-schedule bpm start-time-ms beat-count)
          ticker (run-ticker! schedule)
          state (read-beats! bpm start-time-ms beat-count)]
      (future-cancel ticker)
      (println)
      (println "=== RESULT ===")
      (let [result (core/summary state)]
        (println (format "score=%d max-combo=%d accuracy=%.2f groove=%.2f grade=%s"
                          (:score result)
                          (:max-combo result)
                          (double (:accuracy result))
                          (double (:groove result))
                          (name (:grade result)))))
      ;; futureはclojure.lang.Agentの非daemonスレッドプールを使うため、
      ;; これを呼ばないとロジック完了後もJVMプロセスが終了せずハングする。
      (shutdown-agents))))
