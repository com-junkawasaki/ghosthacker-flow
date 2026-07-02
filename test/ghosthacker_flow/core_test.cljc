(ns ghosthacker-flow.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ghosthacker-flow.core :as core]))

(deftest beat-phase-ms-test
  (testing "拍のちょうど上ではズレ0"
    (is (== 0.0 (core/beat-phase-ms 120 0 0)))
    (is (== 0.0 (core/beat-phase-ms 120 0 500))))
  (testing "次の拍寄りの入力は負のズレに正規化される"
    (is (== -100.0 (core/beat-phase-ms 120 0 400)))))

(deftest beat-interval-ms-guard-test
  (testing "bpm<=0は無音でNaN/Infinityを生まず例外で弾く（ホスト側の曲設定バグを早期発見）"
    (is (thrown? #?(:clj AssertionError :cljs js/Error) (core/beat-interval-ms 0)))
    (is (thrown? #?(:clj AssertionError :cljs js/Error) (core/beat-interval-ms -10)))
    (is (thrown? #?(:clj AssertionError :cljs js/Error) (core/beat-phase-ms 0 0 0)))
    (is (thrown? #?(:clj AssertionError :cljs js/Error) (core/beat-schedule -60 0 4))))
  (testing "正のbpmはこれまで通り"
    (is (== 500.0 (core/beat-interval-ms 120)))))

(deftest judge-test
  (is (= :perfect (core/judge 10)))
  (is (= :perfect (core/judge -30)))
  (is (= :good (core/judge 60)))
  (is (= :miss (core/judge 120))))

(deftest judge-with-windows-test
  (is (= :perfect (core/judge-with-windows 40 45 110)))
  (is (= :good (core/judge-with-windows 40 18 50)))
  (is (= :miss (core/judge-with-windows 40 10 30)))
  (testing "judgeはjudge-with-windowsの:normal相当のラッパー"
    (is (= (core/judge 40) (core/judge-with-windows 40 core/perfect-window-ms core/good-window-ms)))))

(deftest difficulty-presets-test
  (is (= {:perfect-window-ms core/perfect-window-ms :good-window-ms core/good-window-ms}
         (:normal core/difficulty-presets)))
  (testing "easyはnormalより緩く、hardはnormalより厳しい"
    (is (> (get-in core/difficulty-presets [:easy :perfect-window-ms])
           (get-in core/difficulty-presets [:normal :perfect-window-ms])))
    (is (< (get-in core/difficulty-presets [:hard :perfect-window-ms])
           (get-in core/difficulty-presets [:normal :perfect-window-ms])))))

(deftest judge-input-difficulty-test
  (testing "同じズレでも難易度でperfect/good/missの境界が変わる(120bpm,40ms遅れ)"
    (let [easy-state (core/judge-input-difficulty core/initial-state 120 0 40 :easy)
          normal-state (core/judge-input-difficulty core/initial-state 120 0 40 :normal)
          hard-state (core/judge-input-difficulty core/initial-state 120 0 40 :hard)]
      (is (= :perfect (last (:judgments easy-state))))
      (is (= :good (last (:judgments normal-state))))
      (is (= :good (last (:judgments hard-state))))))
  (testing ":normalはjudge-inputと同じ結果になる"
    (is (= (core/judge-input core/initial-state 120 0 40)
           (core/judge-input-difficulty core/initial-state 120 0 40 :normal)))))

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

(deftest beat-index-test
  (is (= 0 (core/beat-index 120 0 0)))
  (is (= 1 (core/beat-index 120 0 500)))
  (testing "拍間の入力は最寄りの拍のindexに丸められる"
    (is (= 1 (core/beat-index 120 0 400)))
    (is (= 0 (core/beat-index 120 0 100)))))

(deftest judge-input-once-test
  (testing "同じ拍への2回目の入力はタイミングに関わらずcomboをリセットするmiss扱い"
    (let [state1 (core/judge-input-once core/initial-state 120 0 0)
          state2 (core/judge-input-once state1 120 0 10)]
      (is (= :perfect (last (:judgments state1))))
      (is (= 1 (:combo state1)))
      (is (= #{0} (:hit-beat-indices state1)))
      (is (= :miss (last (:judgments state2))))
      (is (zero? (:combo state2)))))
  (testing "次の拍への入力は通常通りcomboが伸びる"
    (let [state (-> core/initial-state
                     (core/judge-input-once 120 0 0)
                     (core/judge-input-once 120 0 10) ; 連打(同じ拍) → miss
                     (core/judge-input-once 120 0 500)) ; 次の拍 → perfect
          ]
      (is (= [:perfect :miss :perfect] (:judgments state)))
      (is (= 1 (:combo state)))
      (is (= #{0 1} (:hit-beat-indices state))))))

(deftest judge-sequence-once-test
  (testing "beat-scheduleどおりの入力なら連打ガードに引っかからず全perfect"
    (let [schedule (core/beat-schedule 120 0 6)
          state (core/judge-sequence-once core/initial-state 120 0 schedule)]
      (is (every? #(= :perfect %) (:judgments state)))
      (is (= 6 (:max-combo state)))))
  (testing "同じ拍に対する連打を混ぜるとその分だけmissになる"
    (let [schedule (core/beat-schedule 120 0 3) ; [0.0 500.0 1000.0]
          mashed (into schedule [10.0 20.0])    ; 拍0への連打を2回追加
          state (core/judge-sequence-once core/initial-state 120 0 mashed)]
      (is (= [:perfect :perfect :perfect :miss :miss] (:judgments state))))))

(deftest judgment-direction-test
  (is (= :late (core/judgment-direction 10)))
  (is (= :early (core/judgment-direction -10)))
  (is (= :exact (core/judgment-direction 0))))

(deftest judge-detailed-test
  (is (= {:judgment :perfect :direction :exact} (core/judge-detailed 0)))
  (is (= {:judgment :good :direction :late} (core/judge-detailed 60)))
  (is (= {:judgment :miss :direction :early} (core/judge-detailed -120))))

(deftest play-test
  (testing "beat-scheduleどおりに入力すればgradeは最高評価"
    (let [schedule (core/beat-schedule 120 0 20)
          result (core/play 120 0 schedule)]
      (is (= :sky-high (:grade result)))
      (is (== 1.0 (:accuracy result)))
      (is (= 20 (:max-combo result)))))
  (testing "連打を混ぜるとaccuracyが落ちる（対マッシュガードがplay経由でも効く）"
    (let [schedule (core/beat-schedule 120 0 3)
          mashed (into schedule [10.0])
          result (core/play 120 0 mashed)]
      (is (< (:accuracy result) 1.0))))
  (testing "何も入力しなければplayは『未プレイ』としてaccuracy=1.0のまま
            （judge-runとの違い。空振りを検出したいならjudge-run/play-runを使う）"
    (is (== 1.0 (:accuracy (core/play 120 0 []))))))

(deftest judge-run-test
  (testing "期待される拍をすべて入力すれば、空振りによる追加missは無い"
    (let [schedule (core/beat-schedule 120 0 5)
          state (core/judge-run 120 0 5 schedule)]
      (is (= 5 (count (:judgments state))))
      (is (every? #(= :perfect %) (:judgments state)))))
  (testing "一部の拍にしか入力しないと、空振りぶんだけ:missが積み増される"
    (let [schedule (core/beat-schedule 120 0 5)
          partial-input (subvec schedule 0 3) ; 先頭3拍だけ入力
          state (core/judge-run 120 0 5 partial-input)]
      (is (= 5 (count (:judgments state))))
      (is (= [:perfect :perfect :perfect :miss :miss] (:judgments state)))))
  (testing "全く入力しなければbeat-count分すべて:miss"
    (let [state (core/judge-run 120 0 4 [])]
      (is (= [:miss :miss :miss :miss] (:judgments state)))
      (is (zero? (:score state))))))

(deftest play-run-test
  (testing "何も入力しなければ、playと違ってaccuracyは0.0（空振りが正しくmiss計上される）"
    (let [result (core/play-run 120 0 4 [])]
      (is (== 0.0 (:accuracy result)))
      (is (= :d (:grade result)))))
  (testing "全拍入力すればplayと同じ最高評価"
    (let [schedule (core/beat-schedule 120 0 20)]
      (is (= (core/play 120 0 schedule)
             (core/play-run 120 0 20 schedule))))))
