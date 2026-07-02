(ns ghosthacker-flow.demo
  "GHOST HACKER: FLOW — runnable CLI demo of the groove-sync core.

  Not a host adapter — no real input/audio/rendering here. Just exercises the
  pure API in ghosthacker-flow.core end-to-end on a few scripted runs and
  prints what it produces, so the API surface can be sanity-checked by
  actually running it instead of only reading unit tests.

  Run: clojure -M -m ghosthacker-flow.demo"
  (:require [ghosthacker-flow.core :as core]))

(defn- print-summary [label result]
  (println (format "%-24s score=%-6d max-combo=%-3d accuracy=%.2f groove=%.2f grade=%s"
                    label
                    (:score result)
                    (:max-combo result)
                    (double (:accuracy result))
                    (double (:groove result))
                    (name (:grade result)))))

(defn -main [& _args]
  (let [bpm core/default-bpm
        beats 16
        schedule (core/beat-schedule bpm 0 beats)]
    (println (format "GHOST HACKER: FLOW — groove-sync core demo (bpm=%s)" bpm))
    (println)
    (print-summary "全perfect(理想入力)"
                    (core/play-run bpm 0 beats schedule))
    (print-summary "半分しか入力しない"
                    (core/play-run bpm 0 beats (subvec schedule 0 (quot beats 2))))
    (print-summary "連打を混ぜる"
                    (core/play-run bpm 0 beats (into schedule [10.0 20.0])))
    (println)
    (println "同じズレ(40ms遅れ)の難易度別判定:")
    (doseq [difficulty [:easy :normal :hard]]
      (let [state (core/judge-input-difficulty core/initial-state bpm 0 40 difficulty)]
        (println (format "  %-8s -> %s" (name difficulty) (name (last (:judgments state)))))))))
