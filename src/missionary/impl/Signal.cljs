(ns missionary.impl.Signal
  (:import missionary.impl.Propagator
           missionary.Cancelled))

(declare sub unsub transfer make)

(deftype Pub [flow process alive done failed value]
  IFn
  (-invoke [_] (Propagator/dispatch false Propagator/none))
  IDeref
  (-deref [this] (sub this)))

(deftype Sub [pub]
  IFn
  (-invoke [this] (unsub this))
  IDeref
  (-deref [this] (transfer this)))

(defn sub [^Pub pub]
  (when (nil? (.-process pub))
    (set! (.-process pub)
      ((.-flow pub)
       (Propagator/bind
         #(if (identical? (.-value pub) pub)
            (set! (.-value pub) nil)
            (do (set! (.-value pub) pub)
                (Propagator/schedule))))
       (Propagator/bind
         #(do (set! (.-done pub) true)
              (Propagator/dispatch true Propagator/none)))))
    (if (identical? (.-value pub) pub)
      (do)                                                  ;; TODO
      (set! (.-value pub) pub)))
  (Propagator/attach)
  (Propagator/detach false Propagator/none)
  (set! (.-alive pub) (inc (.-alive pub)))
  (->Sub pub))

(defn unsub [^Sub sub]
  (when-some [pub (.-pub sub)]
    (let [alive (.-alive pub)]
      (if (== 1 alive)
        (when-some [flow (.-flow pub)]
          (set! (.-flow pub) nil)
          (Propagator/reset (make flow))
          ((.-process pub)))
        (do (set! (.-pub sub) nil)
            (set! (.-alive pub) (dec alive))
            (when (Propagator/attached)
              (Propagator/detach false Propagator/none)))))))

(defn transfer [^Sub sub]
  (if-some [pub (.-pub sub)]
    (let [x (.-value pub)
          x (if (identical? x pub)
              (set! (.-value pub)
                (try (loop []
                       (let [x @(.-process pub)]
                         (if (identical? (.-value pub) pub)
                           x (do (set! (.-value pub) pub)
                                 (recur)))))
                     (catch :default e
                       (set! (.-failed pub) true) e))) x)]
      (Propagator/attach)
      (when (.-done pub) (Propagator/detach true Propagator/none))
      (if (.-failed pub) (throw x) x))
    (do (Propagator/attach)
        (Propagator/detach true Propagator/none)
        (throw (Cancelled. "Signal subscription cancelled.")))))

(defn make [flow]
  (let [pub (->Pub flow nil 0 false false nil)]
    (set! (.-value pub) pub)))