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
