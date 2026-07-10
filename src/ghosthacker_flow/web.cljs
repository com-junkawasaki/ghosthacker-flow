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
  nearest chart beat -- a keydown (Space) instead of a `read-line`.

  Visual layer: `kotoba-lang/webgpu` (`kami.webgpu` + `kami.webgpu.ir`) --
  declarative WebGPU-from-EDN, no Rust/wasm (CLAUDE.md's 2026-07-10 rule:
  don't author new Rust crates for app/game rendering; this repo's WebGPU
  scene is composed as plain EDN instances and drawn by that library's
  existing browser executor, the same one network-isekai already uses
  live). Renders the 情報場 (information field) as a handful of drifting
  cuboid \"log particles\" (証拠の欠片) whose colour follows `:groove`
  exactly like the CSS crossfade it sits behind. Degrades silently (falls
  back to the CSS-only background) with no WebGPU support (jsdom, older
  browsers) -- `kami.webgpu/init!` rejects its promise in that case,
  caught below, same graceful-degradation spirit as the Web Audio path."
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [ghosthacker.groove.core :as core]
            [kami.webgpu :as gpu]
            [kami.webgpu.ir :as wir]))

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

;; --- WebGPU visual layer (情報場 / information field) ----------------------

(defonce ^:private !gpu-ctx (atom nil))

(def ^:private particle-count 12)

(def ^:private particle-seeds
  "Deterministic scatter positions for the log particles (fixed, not
  random, so the scene is reproducible run to run -- same spirit as the
  portfolio's other sample data being fixed rather than shuffled)."
  (vec (for [i (range particle-count)]
         {:x (- (* (mod (* i 7) particle-count) 1.6) 9)
          :z (- (* (mod (* i 5) particle-count) 1.4) 7)
          :phase (* i 0.6)})))

(defn- hsl->rgb
  "h in [0,360), s/l in [0,1] -> [r g b] in [0,1]. A small local helper --
  no external color library needed for this one conversion."
  [h s l]
  (let [c (* (- 1 (js/Math.abs (- (* 2 l) 1))) s)
        h' (/ h 60.0)
        x (* c (- 1 (js/Math.abs (- (mod h' 2) 1))))
        [r1 g1 b1] (cond
                     (< h' 1) [c x 0]
                     (< h' 2) [x c 0]
                     (< h' 3) [0 c x]
                     (< h' 4) [0 x c]
                     (< h' 5) [x 0 c]
                     :else    [c 0 x])
        m (- l (/ c 2))]
    [(+ r1 m) (+ g1 m) (+ b1 m)]))

(defn- field-scene
  "The render-IR for one frame: a sky tinted by :groove (TENSE cool blue
  <-> Sky High warm gold, same hue sweep as the CSS crossfade behind it)
  and `particle-count` drifting cuboids -- the ログの粒子 (log particles /
  evidence fragments) FLOW's concept describes the player skating across
  the information field to collect."
  [t-sec groove]
  (let [hue (- 220 (* groove 180))
        color (hsl->rgb hue 0.55 0.55)
        [sr sg sb] (hsl->rgb hue 0.35 0.16)]
    (wir/render-ir
     (wir/sky [sr sg sb] [-0.3 -0.9 -0.3] [1.0 0.97 0.9])
     (for [{:keys [x z phase]} particle-seeds]
       (wir/instance [x (+ 0.6 (* 0.4 (js/Math.sin (+ t-sec phase)))) z]
                     color [0.5 0.5]
                     :emissive (+ 0.15 (* 0.35 groove))))
     [0 16 20] [0 0 0])))

(defn- raf!
  "requestAnimationFrame, guarded -- absent in jsdom (this repo's own
  headless verification) and very old browsers. No-ops instead of
  throwing when unavailable."
  [f]
  (when-let [r (.-requestAnimationFrame js/window)]
    (.call r js/window f)))

(defn- ensure-gpu!
  "Initializes kami-webgpu on `canvas` once. No-ops (leaves !gpu-ctx nil,
  the CSS crossfade stays as the whole visual) when WebGPU isn't
  available -- `kami.webgpu/init!` rejects its promise in that case
  (jsdom / older browsers), caught here rather than thrown."
  [canvas]
  (when (and canvas (not @!gpu-ctx))
    (-> (gpu/init! canvas)
        (.then (fn [ctx] (reset! !gpu-ctx ctx)))
        (.catch (fn [_] nil)))))

(defn- gpu-frame!
  "requestAnimationFrame loop: redraws the information field every frame
  from the current :groove (defaults to 0.0 -- idle/countdown still show
  a calm TENSE field). No-ops (just reschedules) until GPU init resolves."
  [now-ms-val]
  (when-let [ctx @!gpu-ctx]
    (let [groove (get-in @state [:groove-state :groove] 0.0)]
      (gpu/draw! ctx (field-scene (/ now-ms-val 1000.0) groove))))
  (raf! gpu-frame!))

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
  pure core's :groove is meant to drive. Translucent (not opaque) so the
  WebGPU information-field scene shows through behind this panel when
  available; the panel alone still reads fine as the whole visual with
  no WebGPU support."
  [g]
  (let [hue (- 220 (* g 180))]
    {:background (str "linear-gradient(135deg, hsla(" hue ",70%,14%,0.75), hsla(" hue ",70%,24%,0.75))")}))

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
    (ensure-gpu! (.getElementById js/document "flow-canvas"))
    (raf! gpu-frame!)
    (rdom/render [app] el)))

(defn ^:export init [] (mount))
