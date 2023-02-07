(ns missionary.impl.Memo
  (:import missionary.impl.Propagator
           missionary.Cancelled))

(declare run sub unsub make)

(deftype Pub [task process failed result alive]
  IFn
  (-invoke [this] (run this))
  IDeref
  (-deref [this] (sub this)))

(deftype Sub [pub]
  IFn
  (-invoke [this] (unsub this)))

(defn run [^Pub pub]
  (set! (.-alive pub) 0)
  (Propagator/dispatch (.-failed pub) (.-result pub)))

(defn sub [^Pub pub]
  (when (nil? (.-process pub))
    (set! (.-process pub)
      ((.-task pub)
       (Propagator/bind
         (fn [x]
           (set! (.-result pub) x)
           (when-not (nil? (.-process pub))
             (Propagator/schedule))))
       (Propagator/bind
         (fn [x]
           (set! (.-result pub) x)
           (set! (.-failed pub) true)
           (when-not (nil? (.-process pub))
             (Propagator/schedule)))))))
  (Propagator/attach)
  (if (identical? (.-result pub) pub)
    (set! (.-alive pub) (inc (.-alive pub)))
    (Propagator/detach (.-failed pub) (.-result pub)))
  (->Sub pub))

(defn unsub [^Sub sub]
  (when-some [pub (.-pub sub)]
    (set! (.-pub sub) nil)
    (when (identical? (.-result pub) pub)
      (let [alive (.-alive pub)]
        (if (== 1 alive)
          (do (Propagator/reset (make (.-task pub)))
              ((.-process pub)))
          (do (set! (.-alive pub) (dec alive))
              (Propagator/detach true (Cancelled. "Memo subscription cancelled."))))))))

(defn make [task]
  (let [pub (->Pub task nil false nil 0)]
    (set! (.-result pub) pub)))