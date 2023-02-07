(ns missionary.impl.Stream
  (:import missionary.impl.Propagator
           missionary.Cancelled))

(declare run sub unsub transfer make)

(deftype Pub [flow process ^boolean ready ^boolean failed ^boolean done value ^number attached ^number pending ^number step]
  IFn
  (-invoke [this] (run this))
  IDeref
  (-deref [this] (sub this)))

(deftype Sub [pub]
  IFn
  (-invoke [this] (unsub this))
  IDeref
  (-deref [this] (transfer this)))

(defn emit [^Pub pub]
  (set! (.-pending pub) (.-attached pub))
  (set! (.-attached pub) 0)
  (set! (.-value pub) pub)
  (set! (.-ready pub) false)
  (set! (.-step pub) (Propagator/step))
  (Propagator/dispatch false Propagator/none))

(defn run [^Pub pub]
  (set! (.-ready pub) true)
  (when (zero? (.-pending pub))
    (emit pub)))

(defn sub [^Pub pub]
  (when (nil? (.-process pub))
    (set! (.-process pub)
      ((.-flow pub)
       (Propagator/bind
         #(if (nil? (.-process pub))
            (emit pub) (Propagator/schedule)))
       (Propagator/bind
         #(do (set! (.-done pub) true)
              (set! (.-attached pub) 0)
              (Propagator/dispatch true Propagator/none))))))
  (Propagator/attach)
  (if (== (.-step pub) (Propagator/step))
    (do (set! (.-pending pub) (inc (.-pending pub)))
        (Propagator/detach false Propagator/none))
    (set! (.-attached pub) (inc (.-attached pub))))
  (->Sub pub))

(defn unsub [^Sub sub]
  (when-some [pub (.-pub sub)]
    (when-not (.-done pub)
      (let [attached (.-attached pub)
            pending (.-pending pub)]
        (if (== 1 (+ attached pending))
          (when-some [flow (.-flow pub)]
            (set! (.-flow pub) nil)
            (Propagator/reset (make flow))
            ((.-process pub)))
          (do (set! (.-pub sub) nil)
              (if (Propagator/attached)
                (do (set! (.-attached pub) (dec attached))
                    (Propagator/detach false Propagator/none))
                (do (set! (.-pending pub) (dec pending))
                    (when (== 1 pending) (when (.-ready pub) (emit pub)))))))))))

(defn transfer [^Sub sub]
  (if-some [pub (.-pub sub)]
    (let [x (.-value pub)
          x (if (identical? x pub)
              (set! (.-value pub)
                (try @(.-process pub)
                     (catch :default e
                       (set! (.-failed pub) true)
                       e))) x)]
      (Propagator/attach)
      (if (.-done pub)
        (Propagator/detach true Propagator/none)
        (do (set! (.-attached pub) (inc (.-attached pub)))
            (when (zero? (set! (.-pending pub) (dec (.-pending pub))))
              (when (.-ready pub) (emit pub)))))
      (if (.-failed pub) (throw x) x))
    (do (Propagator/attach)
        (Propagator/detach true Propagator/none)
        (throw (Cancelled. "Stream subscription cancelled.")))))

(defn make [flow]
  (->Pub flow nil false false false nil 0 0 -1))