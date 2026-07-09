(ns ghosthacker-flow.web
  "GHOST HACKER: FLOW -- browser host adapter (ADR-2607100900 follow-up
  (b)). ClojureScript, not kotoba wasm/clojurewasm: real-time beat timing
  and audio are host-imports neither can provide yet (ADR-2607100030
  addendum 2's clojurewasm constraint; kotoba wasm's closed capability
  surface doesn't cover audio scheduling either).

  No composed music assets exist for the two crossfading layers the pure
  core's docstring describes (`ghosthacker.groove.core`'s `:groove` is
  \"meant to be handed to an audio host adapter that mixes between two
  music layers\") -- fabricating music is out of scope here. Instead:
  Web Audio drives BOTH the beat clock (`AudioContext.currentTime`, more
  precise than `performance.now()` for scheduled audio) and a
  synthesized metronome tick (an oscillator blip, no external asset
  needed), while `:groove` drives a visual TENSE(cool)<->Sky High(warm)
  crossfade instead of an audio one. Falls back to `js/performance.now`
  with no audible tick when Web Audio is unavailable (e.g. this repo's
  own headless verification script), same graceful-degradation spirit as
  the rest of this monorepo's host-import facades.

  Same input/judgment shape as ghosthacker_flow/terminal.clj: one input
  event per chart beat, judged with `core/judge-chart-input` against the
  nearest chart beat -- a keydown (Space) instead of a `read-line`."
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [ghosthacker.groove.core :as core]))

;; --- clock / audio ----------------------------------------------------

(defonce ^:private !ctx (atom nil))

(defn- ensure-ctx! []
  (when-not @!ctx
    (when-let [ctor (or (.-AudioContext js/window) (.-webkitAudioContext js/window))]
      (reset! !ctx (new ctor))))
  @!ctx)

(defn- now-ms []
  (if-let [ctx @!ctx]
    (* 1000 (.-currentTime ctx))
    (.now js/performance)))

(defn- schedule-tick!
  "Schedules a short synthesized blip at absolute t-ms via the audio
  clock (sample-accurate, unlike setTimeout). No-ops silently with no
  AudioContext (headless / unsupported browser)."
  [t-ms freq]
  (when-let [ctx @!ctx]
    (let [t (/ t-ms 1000.0)
          osc (.createOscillator ctx)
          gain (.createGain ctx)]
      (set! (.-value (.-frequency osc)) freq)
      (.setValueAtTime (.-gain gain) 0.001 t)
      (.linearRampToValueAtTime (.-gain gain) 0.25 (+ t 0.005))
      (.exponentialRampToValueAtTime (.-gain gain) 0.001 (+ t 0.09))
      (.connect osc gain)
      (.connect gain (.-destination ctx))
      (.start osc t)
      (.stop osc (+ t 0.1)))))

;; --- chart --------------------------------------------------------------

(defn- default-chart
  "TENSE(default bpm, first half) -> Sky High(1.25x, second half), same
  2-section shape as terminal.clj's default-chart."
  [start-time-ms beat-count]
  (let [tense (quot beat-count 2)
        climax (- beat-count tense)]
    (core/chart-beats start-time-ms
                       [{:bpm core/default-bpm :beat-count tense}
                        {:bpm (long (* 1.25 core/default-bpm)) :beat-count climax}])))

;; --- state ----------------------------------------------------------------

(defonce state
  (r/atom {:phase :idle          ; :idle | :countdown | :playing | :result
           :beat-count 8
           :chart nil
           :groove-state nil
           :beats-done 0
           :last-judgment nil
           :countdown-label "3"}))

(defn- hit! []
  (when (= (:phase @state) :playing)
    (let [t (now-ms)
          {:keys [chart groove-state beats-done]} @state
          next-gs (core/judge-chart-input groove-state chart t)
          judgment (last (:judgments next-gs))
          done (inc beats-done)]
      (swap! state assoc
             :groove-state next-gs
             :last-judgment judgment
             :beats-done done
             :phase (if (>= done (count chart)) :result :playing)))))

(defn- start-game! []
  (ensure-ctx!)
  (let [beat-count (:beat-count @state)
        interval (core/beat-interval-ms core/default-bpm)
        go-time-ms (+ (now-ms) (* 3 interval))
        chart (default-chart go-time-ms beat-count)]
    (swap! state assoc
           :phase :countdown
           :chart chart
           :groove-state core/initial-state
           :beats-done 0
           :last-judgment nil
           :countdown-label "3")
    (schedule-tick! (- go-time-ms (* 3 interval)) 440)
    (schedule-tick! (- go-time-ms (* 2 interval)) 440)
    (schedule-tick! (- go-time-ms interval) 440)
    (doseq [t chart] (schedule-tick! t 880))
    (doseq [[i label] (map-indexed vector ["3" "2" "1"])]
      (js/setTimeout #(swap! state assoc :countdown-label label) (* i interval)))
    (js/setTimeout #(swap! state assoc :countdown-label "GO!" :phase :playing) (* 3 interval))))

(defn- restart! [] (swap! state assoc :phase :idle))

;; --- keyboard input ---------------------------------------------------

(defn- on-keydown [e]
  (when (= (.-code e) "Space")
    (.preventDefault e)
    (hit!)))

;; --- views ------------------------------------------------------------

(defn- groove-bg
  "TENSE(g=0, cool blue) -> Sky High(g=1, warm gold), same crossfade the
  pure core's :groove is meant to drive, rendered visually instead of
  as an audio layer mix (no composed music assets exist to mix)."
  [g]
  (let [hue (- 220 (* g 180))]
    {:background (str "linear-gradient(135deg, hsl(" hue ",70%,14%), hsl(" hue ",70%,24%))")}))

(defn- start-screen []
  [:div.flow-app
   [:h1 "GHOST HACKER: FLOW"]
   [:p.flow-sub "情報場を滑走し、ビートに合わせて Space を叩け。"]
   [:button {:on-click start-game!} "START"]])

(defn- countdown-screen []
  [:div.flow-app
   [:h1 "GHOST HACKER: FLOW"]
   [:div.flow-countdown (:countdown-label @state)]])

(defn- playing-screen []
  (let [{:keys [groove-state last-judgment beats-done chart]} @state]
    [:div.flow-app {:style (groove-bg (:groove groove-state))}
     [:h1 "GHOST HACKER: FLOW"]
     [:div.flow-hud
      [:span (str "beat " beats-done "/" (count chart))]
      [:span (str "combo " (:combo groove-state))]
      [:span (str "groove " (.toFixed (:groove groove-state) 2))]]
     [:div.flow-judgment (when last-judgment (name last-judgment))]
     [:p.flow-hint "Space で入力"]]))

(defn- result-screen []
  (let [summary (core/summary (:groove-state @state))]
    [:div.flow-app
     [:h1 "GHOST HACKER: FLOW"]
     [:h2 (str "grade: " (name (:grade summary)))]
     [:p (str "score " (:score summary) " / max-combo " (:max-combo summary))]
     [:p (str "accuracy " (.toFixed (* 100 (:accuracy summary)) 0) "% / groove " (.toFixed (:groove summary) 2))]
     [:button {:on-click restart!} "もう一度"]]))

(defn app []
  (case (:phase @state)
    :countdown [countdown-screen]
    :playing [playing-screen]
    :result [result-screen]
    [start-screen]))

(defn ^:export mount []
  (when-let [el (.getElementById js/document "app")]
    (.addEventListener js/window "keydown" on-keydown)
    (rdom/render [app] el)))

(defn ^:export init [] (mount))
