(ns missionary.impl.Seed
  (:import missionary.Cancelled))

(declare cancel transfer)
(deftype Process
  [iterator notifier terminator]
  IFn
  (-invoke [ps] (cancel ps))
  IDeref
  (-deref [ps] (transfer ps)))

(defn cancel [^Process ps]
  (set! (.-iterator ps) nil))

(defn more [^Process ps i]
  (if (.hasNext i)
    ((.-notifier ps))
    (do (set! (.-iterator ps) nil)
        ((.-terminator ps)))))

(defn transfer [^Process ps]
  (if-some [i (.-iterator ps)]
    (let [x (.next i)]
      (more ps i) x)
    (do ((.-terminator ps))
        (throw (Cancelled. "Seed cancelled")))))

(defn run [coll n t]
  (let [i (iter coll)
        ps (->Process i n t)]
    (more ps i) ps))
