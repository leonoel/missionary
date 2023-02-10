(ns missionary.impl.Sleep
  (:import missionary.Cancelled))

(declare cancel)
(deftype Process
  [failure handler
   ^boolean pending]
  IFn
  (-invoke [s] (cancel s)))

(defn cancel [^Process s]
  (when (.-pending s)
    (set! (.-pending s) false)
    (js/clearTimeout (.-handler s))
    ((.-failure s) (Cancelled. "Sleep cancelled."))))

(defn run [d x s f]
  (let [slp (->Process f nil true)]
    (set! (.-handler slp) (js/setTimeout #(do (set! (.-pending slp) false) (s x)) d)) slp))

