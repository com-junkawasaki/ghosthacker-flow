(ns ghosthacker-flow.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ghosthacker-flow.core :as core]))

(deftest beat-phase-ms-test
  (testing "拍のちょうど上ではズレ0"
    (is (== 0.0 (core/beat-phase-ms 120 0 0)))
    (is (== 0.0 (core/beat-phase-ms 120 0 500))))
  (testing "次の拍寄りの入力は負のズレに正規化される"
    (is (== -100.0 (core/beat-phase-ms 120 0 400)))))

(deftest judge-test
  (is (= :perfect (core/judge 10)))
  (is (= :perfect (core/judge -30)))
  (is (= :good (core/judge 60)))
  (is (= :miss (core/judge 120))))

(deftest groove-crossfade-test
  (testing "perfectを連続で当てるとgrooveがSky High側へ寄り、comboも伸びる"
    (let [state (reduce (fn [s _] (core/apply-judgment s :perfect))
                         core/initial-state (range 10))]
      (is (> (:groove state) 0.5))
      (is (= 10 (:combo state)))))
  (testing "missでcomboがリセットされgrooveが下がる"
    (let [state (-> core/initial-state
                     (core/apply-judgment :perfect)
                     (core/apply-judgment :perfect)
                     (core/apply-judgment :miss))]
      (is (zero? (:combo state)))
      (is (< (:groove state) 0.16))))
  (testing "grooveは0.0〜1.0にclampされる"
    (let [maxed (reduce (fn [s _] (core/apply-judgment s :perfect))
                         core/initial-state (range 50))
          bottomed (reduce (fn [s _] (core/apply-judgment s :miss))
                            core/initial-state (range 50))]
      (is (<= (:groove maxed) 1.0))
      (is (>= (:groove bottomed) 0.0)))))

(deftest judge-input-test
  (testing "ジャストタイミングの入力はperfect判定でstateに反映される"
    (let [state (core/judge-input core/initial-state 120 0 5)]
      (is (= [:perfect] (:judgments state)))
      (is (= 1 (:combo state))))))

(deftest beat-schedule-test
  (is (= [0.0 500.0 1000.0 1500.0] (core/beat-schedule 120 0 4)))
  (is (= [1000.0 1500.0] (core/beat-schedule 120 1000 2)))
  (is (= [] (core/beat-schedule 120 0 0))))

(deftest judge-sequence-test
  (testing "beat-scheduleどおりの入力列を流すと全perfectでmax-comboが伸びる"
    (let [schedule (core/beat-schedule 120 0 8)
          state (core/judge-sequence core/initial-state 120 0 schedule)]
      (is (every? #(= :perfect %) (:judgments state)))
      (is (= 8 (:max-combo state)))))
  (testing "judge-inputを手で畳み込んだ結果と一致する（統合APIが基本APIの合成であること）"
    (let [schedule (core/beat-schedule 100 0 5)
          via-sequence (core/judge-sequence core/initial-state 100 0 schedule)
          via-reduce (reduce (fn [s t] (core/judge-input s 100 0 t))
                              core/initial-state schedule)]
      (is (= via-sequence via-reduce)))))

(deftest combo-multiplier-test
  (is (== 1.0 (core/combo-multiplier 0)))
  (is (== 1.25 (core/combo-multiplier 25)))
  (testing "上限(50)で頭打ち、それ以上は増えない"
    (is (== 1.5 (core/combo-multiplier 50)))
    (is (== 1.5 (core/combo-multiplier 500)))))

(deftest score-test
  (testing "1回目のperfectはcombo倍率がまだ低い(combo=1 → x1.01)"
    (let [state (core/apply-judgment core/initial-state :perfect)]
      (is (= 1010 (:score state)))))
  (testing "missはscoreを増やさない"
    (let [state (-> core/initial-state
                     (core/apply-judgment :perfect)
                     (core/apply-judgment :miss))]
      (is (= 1010 (:score state)))))
  (testing "comboが伸びるほど同じperfectでも加点が増える"
    (let [after-1 (core/apply-judgment core/initial-state :perfect)
          after-60 (reduce (fn [s _] (core/apply-judgment s :perfect))
                            core/initial-state (range 60))
          gain-1 (:score after-1)
          gain-60th (- (:score after-60)
                       (:score (reduce (fn [s _] (core/apply-judgment s :perfect))
                                       core/initial-state (range 59))))]
      (is (> gain-60th gain-1)))))

(deftest max-combo-test
  (testing "max-comboはmiss後もリセットされず最高値を保持する"
    (let [state (-> core/initial-state
                     (core/apply-judgment :perfect)
                     (core/apply-judgment :perfect)
                     (core/apply-judgment :perfect)
                     (core/apply-judgment :miss)
                     (core/apply-judgment :perfect))]
      (is (= 3 (:max-combo state)))
      (is (= 1 (:combo state))))))

(deftest accuracy-test
  (is (== 1.0 (core/accuracy core/initial-state)))
  (let [state (-> core/initial-state
                   (core/apply-judgment :perfect)
                   (core/apply-judgment :good)
                   (core/apply-judgment :miss)
                   (core/apply-judgment :miss))]
    (is (== 0.5 (core/accuracy state)))))

(deftest grade-test
  (testing "全perfectでgroove/accuracyともに高ければ :sky-high"
    (let [state (reduce (fn [s _] (core/apply-judgment s :perfect))
                         core/initial-state (range 20))]
      (is (= :sky-high (core/grade state)))))
  (testing "全missなら :d"
    (let [state (reduce (fn [s _] (core/apply-judgment s :miss))
                         core/initial-state (range 5))]
      (is (= :d (core/grade state))))))

(deftest summary-test
  (let [state (reduce (fn [s _] (core/apply-judgment s :perfect))
                       core/initial-state (range 5))
        result (core/summary state)]
    (is (= #{:score :max-combo :accuracy :groove :grade :judgment-count}
           (set (keys result))))
    (is (= 5 (:judgment-count result)))
    (is (= 5 (:max-combo result)))))
