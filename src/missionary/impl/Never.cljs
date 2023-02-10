(ns missionary.impl.Never
  (:import missionary.Cancelled))

(declare cancel)
(deftype Process [f ^boolean alive]
  IFn (-invoke [ps] (cancel ps)))

(defn cancel [^Process ps]
  (when (.-alive ps)
    (set! (.-alive ps) false)
    ((.-f ps) (Cancelled. "Never cancelled."))))

(defn run [f] (->Process f true))
