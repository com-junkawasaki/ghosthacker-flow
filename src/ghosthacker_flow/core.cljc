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
        ;; Clojureのmodはfloored divisionなので、interval(>0が保証済み)に対し
        ;; 常に[0, interval)を返す(Javaの%のような負の余りにはならない)。
        phase (mod elapsed interval)]
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

(defn judge-detailed-with-windows
  "judge-detailedの判定窓パラメータ版。judge-with-windowsとdifficulty-presets
   を組み合わせれば、難易度ごとの『はやい!/おそい!』表示も作れる。"
  [delta-ms perfect-window good-window]
  {:judgment (judge-with-windows delta-ms perfect-window good-window)
   :direction (judgment-direction delta-ms)})

(defn judge-detailed
  "judgeと同じ判定に、早い/遅い/ジャストの方向を添えて返す（:normal相当の
   既定窓）。ホストアダプタ側の『はやい!/おそい!』のようなタイミング
   フィードバック表示に使う想定。"
  [delta-ms]
  (judge-detailed-with-windows delta-ms perfect-window-ms good-window-ms))

(defn judge-detailed-difficulty
  "judge-detailedの難易度対応版。difficultyはdifficulty-presetsのキー
   (:easy/:normal/:hard)。judge-input-difficultyと対になる、判定窓だけ
   難易度で差し替えるバリアント。"
  [delta-ms difficulty]
  (let [{:keys [perfect-window-ms good-window-ms]} (get difficulty-presets difficulty)]
    (judge-detailed-with-windows delta-ms perfect-window-ms good-window-ms)))

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

;; --- chart（複数セクション/可変bpm）判定 -------------------------------------
;;
;; ここまでのjudge-*/beat-*系は「run全体を通して単一bpm」が前提——
;; TENSE(遅め/疎)→Sky High(速め/密)のようにセクションごとにbpmや拍密度が
;; 変わる曲を1本の判定対象として扱えない。chart-beatsで複数セクションを
;; 連結した「拍の絶対時刻msの昇順vector」を作り、judge-chart-*系はそれを
;; 唯一の入力として判定する（bpmをその都度渡す必要が無い——chart自体が
;; 拍グリッドの完全な記述）。

(defn section-beats
  "1セクション分の拍の絶対時刻(ms)を返す。{:bpm :start-ms :beat-count}を
   受け取り、beat-scheduleと同じ計算をする（chart合成用の呼び名）。"
  [{:keys [bpm start-ms beat-count]}]
  (beat-schedule bpm start-ms beat-count))

(defn section-duration-ms
  "1セクションの長さ(ms)。次セクションのstart-msを自動で継ぎ足す時に使う。"
  [{:keys [bpm beat-count]}]
  (* beat-count (beat-interval-ms bpm)))

(defn chart-beats
  "sections（各 {:bpm :beat-count}、:start-msは省略可）を時系列に連結した
   1本のchart（拍の絶対時刻msの昇順vector）を返す。各セクションの
   :start-msは直前セクションの終了時刻から自動で継ぎ足すため、曲の構成
   （イントロは疎/遅め、サビは密/速め、のようなTENSE→Sky High展開）を
   セクション単位でオーサリングできる。"
  [start-time-ms sections]
  (loop [t start-time-ms
         secs sections
         acc []]
    (if-let [sec (first secs)]
      (let [beats (section-beats (assoc sec :start-ms t))]
        (recur (+ t (section-duration-ms sec)) (rest secs) (into acc beats)))
      acc)))

(defn nearest-chart-beat
  "chart（拍の絶対時刻msの昇順vector）からinput-time-msに最も近い拍の
   {:index :offset-ms}を返す（offset-msは符号付き、正=遅い/負=早い）。
   chartが空ならnil。"
  [chart input-time-ms]
  (when (seq chart)
    (let [[idx t] (apply min-key
                         (fn [[_ t]] (magnitude (- input-time-ms t)))
                         (map-indexed vector chart))]
      {:index idx :offset-ms (- input-time-ms t)})))

(defn judge-chart-input
  "bpm/start-time-msの単一グリッド前提を外し、chart（chart-beatsで作った
   拍の絶対時刻ms列）に対してinput-time-msを1回判定する。judge-input-once
   と同じ対マッシュガードつき（既に成立済みのchart indexへの追加入力は、
   タイミング精度に関わらずcomboをリセットする:missとして扱う）。"
  [state chart input-time-ms]
  (if-let [{:keys [index offset-ms]} (nearest-chart-beat chart input-time-ms)]
    (if (contains? (:hit-beat-indices state) index)
      (apply-judgment state :miss)
      (-> state
          (apply-judgment (judge offset-ms))
          (update :hit-beat-indices conj index)))
    (apply-judgment state :miss)))

(defn judge-chart-sequence
  "input-times（時系列順）をまとめてjudge-chart-inputで畳み込む。"
  [state chart input-times]
  (reduce (fn [s t] (judge-chart-input s chart t)) state input-times))

(defn chart-run
  "chart全体のうち、input-timesで一度も成立しなかった拍のぶんだけ末尾に
   明示的な:missを積み増す（judge-runのchart版。空振りを空振りとして
   数える）。"
  [chart input-times]
  (let [after-inputs (judge-chart-sequence initial-state chart input-times)
        hit? (:hit-beat-indices after-inputs)
        missed-count (count (remove hit? (range (count chart))))]
    (reduce (fn [s _] (apply-judgment s :miss))
            after-inputs
            (range missed-count))))

(defn chart-play-run
  "chart-runの結果をsummaryにして返す（play-runのchart版）。"
  [chart input-times]
  (summary (chart-run chart input-times)))
