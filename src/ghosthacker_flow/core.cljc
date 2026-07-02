(ns ghosthacker-flow.core
  "GHOST HACKER: FLOW — groove-sync core (ADR-2607023200).

  Pure, host-free judgment/state engine for the FLOW action loop: the player
  rides a four-on-the-floor beat grid through the 情報場 (information field)
  and picks up log fragments in time with it. How well input timing tracks
  the beat drives `:groove`, a 0.0(TENSE)..1.0(Sky High) crossfade parameter
  meant to be handed to an audio host adapter that mixes between the two
  FreeTEMPO-flavored music layers described in the ADR. No rendering, input,
  or audio I/O lives here — those are host adapters layered on top, mirroring
  the ghosthacker.resources (pure) / ghosthacker.import (host) split already
  used elsewhere in this org.")

(def default-bpm
  "FreeTEMPO寄りの温かい四つ打ちハウスのグルーヴ帯。"
  124)

(def perfect-window-ms 30)
(def good-window-ms 80)

(defn beat-interval-ms [bpm]
  (/ 60000.0 bpm))

(defn beat-phase-ms
  "start-time-ms を 0 拍目として、input-time-ms が直近の拍からどれだけ
   ずれているか（ms、符号付き、区間は (-interval/2, interval/2]）を返す。"
  [bpm start-time-ms input-time-ms]
  (let [interval (beat-interval-ms bpm)
        elapsed (- input-time-ms start-time-ms)
        phase (mod elapsed interval)
        phase (if (neg? phase) (+ phase interval) phase)]
    (if (> phase (/ interval 2))
      (- phase interval)
      phase)))

(defn- magnitude [x]
  (if (neg? x) (- x) x))

(defn judge
  "ズレ(ms、符号は問わない)から判定を返す。"
  [delta-ms]
  (let [abs-delta (magnitude (double delta-ms))]
    (cond
      (<= abs-delta perfect-window-ms) :perfect
      (<= abs-delta good-window-ms) :good
      :else :miss)))

(def initial-state
  {:combo 0
   :max-combo 0
   :score 0
   :groove 0.0 ; 0.0=TENSE, 1.0=Sky High。音楽crossfadeのミックスパラメータ
   :judgments []})

(def ^:private groove-delta
  {:perfect 0.08
   :good 0.03
   :miss -0.15})

(def ^:private base-score
  {:perfect 1000
   :good 400
   :miss 0})

(def ^:private max-combo-multiplier-bonus
  "comboが伸びるほどscoreに乗る倍率の上限ボーナス（+50%でcap）。
   グルーヴに乗り続けることを続ける動機づけをscore面でも作る。"
  0.5)

(def ^:private combo-multiplier-cap 50)

(defn- clamp01 [x]
  (max 0.0 (min 1.0 x)))

(defn combo-multiplier
  "現在のcomboから score 倍率を返す（1.0〜1.5、comboが伸びるほど上がりcapで頭打ち）。"
  [combo]
  (+ 1.0 (* max-combo-multiplier-bonus
             (/ (min combo combo-multiplier-cap) combo-multiplier-cap))))

(defn apply-judgment
  "judgment を state に反映する。:miss は combo をリセットし groove を大きく
   落とす。:perfect/:good は combo を伸ばし groove を Sky High 側へ寄せ、
   comboに応じた倍率つきでscoreを加算する。"
  [state judgment]
  (let [next-combo (if (= judgment :miss) 0 (inc (:combo state)))
        gained (long (* (get base-score judgment) (combo-multiplier next-combo)))]
    (-> state
        (update :judgments conj judgment)
        (assoc :combo next-combo)
        (update :max-combo max next-combo)
        (update :score + gained)
        (update :groove (fn [g] (clamp01 (+ g (get groove-delta judgment))))))))

(defn judge-input
  "bpm/start-time-ms で定義されたビートグリッドに対する input-time-ms の
   入力を判定し、state に適用した結果を返す。"
  [state bpm start-time-ms input-time-ms]
  (->> (beat-phase-ms bpm start-time-ms input-time-ms)
       judge
       (apply-judgment state)))

(defn accuracy
  "judgmentsのうち :perfect/:good が占める割合（0.0〜1.0）。
   judgmentsが空なら1.0（未プレイをミス扱いにしない）。"
  [state]
  (let [judgments (:judgments state)]
    (if (empty? judgments)
      1.0
      (/ (count (remove #(= % :miss) judgments)) (double (count judgments))))))

(defn grade
  "accuracyとgrooveから最終評価を返す。:sky-high が最高評価
   （高精度でTENSE→Sky Highへ転調しきった状態）。"
  [state]
  (let [acc (accuracy state)
        g (:groove state)]
    (cond
      (and (>= acc 0.95) (>= g 0.8)) :sky-high
      (>= acc 0.85) :a
      (>= acc 0.7) :b
      (>= acc 0.5) :c
      :else :d)))

(defn summary
  "runの結果サマリ。ホストアダプタ側のリザルト画面にそのまま渡せる形。"
  [state]
  {:score (:score state)
   :max-combo (:max-combo state)
   :accuracy (accuracy state)
   :groove (:groove state)
   :grade (grade state)
   :judgment-count (count (:judgments state))})
