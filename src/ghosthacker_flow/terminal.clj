(ns ghosthacker-flow.terminal
  "GHOST HACKER: FLOW — minimal terminal host adapter (playable prototype).

  A real, human-playable loop built with nothing beyond JVM/Clojure already
  in this project: a background thread (`future`) sleeps to each beat's
  wall-clock time and prints a tick; the main thread blocks on `read-line`
  for each beat and judges the real elapsed time against
  `ghosthacker.groove.core` (com-junkawasaki/ghosthacker-groove-core). No
  audio/rendering — just proof that the pure core can drive a genuinely
  timed, interactive loop end-to-end.

  Run: clojure -M -m ghosthacker-flow.terminal [beat-count]"
  (:require [ghosthacker.groove.core :as core]))

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

(defn- countdown!
  "「3, 2, 1, GO!」を1拍分の間隔で表示する。start-time-msはGO!の瞬間の
   時刻(=拍0の基準時刻)にする——プレイヤーがGO!に合わせて最初の入力を
   打ちやすくするための導入演出。"
  [bpm]
  (let [interval-ms (long (core/beat-interval-ms bpm))]
    (doseq [n [3 2 1]]
      (println n)
      (Thread/sleep interval-ms))
    (println "GO!")))

(defn- read-beats!
  "chart(拍の絶対時刻ms列)ぶんread-lineで入力を待ち、judge-chart-inputで
   都度判定して進行状況を印字する。標準入力がEOF(nil)になったら、そこまでの
   stateで打ち切る（実プレイでCtrl-D、テストで空stdinを渡した時の両方に対応）。"
  [chart]
  (loop [state core/initial-state i 0]
    (if (>= i (count chart))
      state
      (let [line (read-line)]
        (if (nil? line)
          state
          (let [now (System/currentTimeMillis)
                next-state (core/judge-chart-input state chart now)
                judgment (last (:judgments next-state))]
            (println (format " -> %s (combo %d)" (name judgment) (:combo next-state)))
            (recur next-state (inc i))))))))

(defn- default-chart
  "既定の曲構成: TENSE(既定bpm/前半)からSky High(1.25倍速/後半)へ加速する
   2セクション。groove-syncのテーマ(乗るほどTENSEからSky Highへ転調)を、
   セクション単位のテンポ変化として演出する。"
  [start-time-ms beat-count]
  (let [tense-count (quot beat-count 2)
        climax-count (- beat-count tense-count)]
    (core/chart-beats start-time-ms
                      [{:bpm core/default-bpm :beat-count tense-count}
                       {:bpm (long (* 1.25 core/default-bpm)) :beat-count climax-count}])))

(defn -main [& args]
  (let [beat-count (if-let [a (first args)] (Integer/parseInt a) 8)]
    (println (format "GHOST HACKER: FLOW — terminal prototype (%d beats, TENSE→Sky High)" beat-count))
    (println "Enterキーで各拍を叩いてください。準備ができたらEnterで開始:")
    (read-line)
    (countdown! core/default-bpm)
    (let [start-time-ms (System/currentTimeMillis)
          chart (default-chart start-time-ms beat-count)
          ticker (run-ticker! chart)
          state (read-beats! chart)]
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
