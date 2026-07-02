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

(def perfect-window-ms
  "既定(:normal)のperfect判定窓(ms)。difficulty-presetsも参照。"
  30)
(def good-window-ms
  "既定(:normal)のgood判定窓(ms)。difficulty-presetsも参照。"
  80)

(defn beat-interval-ms
  "1拍の長さ(ms)。bpmはビートグリッド全体の唯一の入口——ここで弾いておけば
   bpm<=0(0除算/負のinterval)がbeat-phase-ms以下に無音で伝播して
   NaN/Infinityを撒き散らすのを防げる。bpmはホスト側の曲/レベル設定由来の
   値なので、境界での検証として妥当。"
  [bpm]
  {:pre [(pos? bpm)]}
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

(defn judge-with-windows
  "ズレ(ms、符号は問わない)を、渡された perfect/good 判定窓(ms)で判定する。
   judge/difficulty-presets はこれの薄いラッパー。"
  [delta-ms perfect-window good-window]
  (let [abs-delta (magnitude (double delta-ms))]
    (cond
      (<= abs-delta perfect-window) :perfect
      (<= abs-delta good-window) :good
      :else :miss)))

(defn judge
  "ズレ(ms、符号は問わない)から判定を返す（:normal相当の既定窓）。"
  [delta-ms]
  (judge-with-windows delta-ms perfect-window-ms good-window-ms))

(def difficulty-presets
  "判定窓のプリセット。difficultyが変えるのは『どれだけタイミングに厳しいか』
   だけで、score/combo/grooveの計算式自体は難易度に依らず共通。"
  {:easy   {:perfect-window-ms 45 :good-window-ms 110}
   :normal {:perfect-window-ms perfect-window-ms :good-window-ms good-window-ms}
   :hard   {:perfect-window-ms 18 :good-window-ms 50}})

(defn judgment-direction
  "beat-phase-msの符号から早入力/遅入力/ジャストを返す。
   正=拍より後（遅い）、負=拍より前（早い）というbeat-phase-msの規約に対応。"
  [delta-ms]
  (cond
    (pos? delta-ms) :late
    (neg? delta-ms) :early
    :else :exact))

(defn judge-detailed
  "judgeと同じ判定に、早い/遅い/ジャストの方向を添えて返す。ホストアダプタ側の
   『はやい!/おそい!』のようなタイミングフィードバック表示に使う想定。"
  [delta-ms]
  {:judgment (judge delta-ms)
   :direction (judgment-direction delta-ms)})

(def initial-state
  {:combo 0
   :max-combo 0
   :score 0
   :groove 0.0 ; 0.0=TENSE, 1.0=Sky High。音楽crossfadeのミックスパラメータ
   :judgments []
   :hit-beat-indices #{}}) ; judge-input-once の対マッシュガードが使う既成立拍の記録

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

(defn judge-input-difficulty
  "judge-inputの難易度対応版。difficultyはdifficulty-presetsのキー
   (:easy/:normal/:hard)。判定窓が変わるだけで、score/combo/grooveへの
   反映(apply-judgment)はjudge-inputと同じ。"
  [state bpm start-time-ms input-time-ms difficulty]
  (let [{:keys [perfect-window-ms good-window-ms]} (get difficulty-presets difficulty)
        delta (beat-phase-ms bpm start-time-ms input-time-ms)]
    (apply-judgment state (judge-with-windows delta perfect-window-ms good-window-ms))))

(defn judge-sequence
  "input-times（時系列順の ms タイムスタンプ列）をまとめて judge-input で
   畳み込む。ホストアダプタが1入力ごとに呼ぶ代わりに、収録済みの入力列や
   テストのリプレイをまとめて評価したい時のための統合API。"
  [state bpm start-time-ms input-times]
  (reduce (fn [s t] (judge-input s bpm start-time-ms t))
          state
          input-times))

(defn- round-int
  "host固有のMath関数を使わない四捨五入（0からの方向へ丸め）。"
  [x]
  (long (+ x (if (neg? x) -0.5 0.5))))

(defn beat-index
  "input-time-ms が属する最寄りの拍のインデックス(0始まり、負も許容)を返す。"
  [bpm start-time-ms input-time-ms]
  (let [interval (beat-interval-ms bpm)
        elapsed (- input-time-ms start-time-ms)
        phase (beat-phase-ms bpm start-time-ms input-time-ms)]
    (round-int (/ (- elapsed phase) interval))))

(defn judge-input-once
  "judge-inputと同じ判定をするが、同じ拍(beat-index)への二重入力（連打での
   スコア稼ぎ）を検出する。既に成立済みの拍への追加入力は、そのタイミング
   精度に関わらず combo をリセットする :miss として扱う——マッシュ連打で
   comboもscoreも稼げないようにするガード。"
  [state bpm start-time-ms input-time-ms]
  (let [idx (beat-index bpm start-time-ms input-time-ms)]
    (if (contains? (:hit-beat-indices state) idx)
      (apply-judgment state :miss)
      (-> state
          (judge-input bpm start-time-ms input-time-ms)
          (update :hit-beat-indices conj idx)))))

(defn judge-sequence-once
  "input-timesをまとめてjudge-input-onceで畳み込む（judge-sequenceの
   対マッシュガード版）。"
  [state bpm start-time-ms input-times]
  (reduce (fn [s t] (judge-input-once s bpm start-time-ms t))
          state
          input-times))

(defn beat-schedule
  "start-time-ms を0拍目として、beat-count個ぶんの拍の絶対時刻(ms)を返す。
   レベル/譜面オーサリングや、テストで『理想入力列』を作る時に使う——
   `(map #(judge-input ...) (beat-schedule ...))` で全perfectのリプレイを
   組み立てられる。"
  [bpm start-time-ms beat-count]
  (let [interval (beat-interval-ms bpm)]
    (mapv #(+ start-time-ms (* % interval)) (range beat-count))))

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

(defn play
  "initial-stateから始めてinput-timesをjudge-sequence-once(対マッシュガード
   つき)で評価し、summaryだけを返す。ホストアダプタが1run分の入力を録り
   終えた後に呼ぶ最短経路。

   注意: この関数は『入力されなかった拍』を知らない——input-timesに何も
   無ければ judgments も空のままで、accuracy は(未プレイ扱いの)1.0になる。
   曲全体で何拍流れるはずだったかが分かっている時は play ではなく
   judge-run / play-run を使うこと（空振りした拍を明示的に:missにする）。"
  [bpm start-time-ms input-times]
  (-> initial-state
      (judge-sequence-once bpm start-time-ms input-times)
      summary))

(defn judge-run
  "beat-countぶんの『流れてくるはずの拍』と、実際のinput-times（対マッシュ
   ガードつきで評価）を突き合わせる。playやjudge-sequence-onceは入力され
   なかった拍を一切知らないため、黙って何も押さなければmissにすらならない
   ——judge-runはinput-timesの評価後、hit-beat-indicesに含まれない拍
   （タイミングに関わらず一度も入力が届かなかった拍）ぶんだけ明示的に
   :missを積み増す。空振りを空振りとして数える。

   簡略化: どの拍が具体的に空振りだったかは判定に使わず、空振りの『件数』
   ぶんだけmissを末尾に追加する（score/combo/grooveへの影響は同じになる
   ため、順序の厳密な再現までは行わない）。"
  [bpm start-time-ms beat-count input-times]
  (let [after-inputs (judge-sequence-once initial-state bpm start-time-ms input-times)
        hit? (:hit-beat-indices after-inputs)
        missed-count (count (remove hit? (range beat-count)))]
    (reduce (fn [s _] (apply-judgment s :miss))
            after-inputs
            (range missed-count))))

(defn play-run
  "judge-runの結果をsummaryにして返す（playのbeat-count対応版）。"
  [bpm start-time-ms beat-count input-times]
  (summary (judge-run bpm start-time-ms beat-count input-times)))
