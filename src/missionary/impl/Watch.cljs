(ns ^:no-doc missionary.impl.Watch
  (:import missionary.Cancelled))

(declare kill transfer)
(deftype Process [notifier terminator reference value]
  IFn
  (-invoke [this] (kill this) nil)
  IDeref
  (-deref [this] (transfer this)))

(defn watch [^Process ps _ _ curr]
  (when-some [cb (.-notifier ps)]
    (let [x (.-value ps)]
      (set! (.-value ps) curr)
      (when (identical? x ps) (cb)))))

(defn kill [^Process ps]
  (when-some [cb (.-notifier ps)]
    (set! (.-notifier ps) nil)
    (let [x (.-value ps)]
      (set! (.-value ps) nil)
      (when (identical? x ps) (cb)))))

(defn transfer [^Process ps]
  (if (nil? (.-notifier ps))
    (do ((.-terminator ps))
        (remove-watch (.-reference ps) ps)
        (throw (Cancelled. "Watch cancelled.")))
    (let [x (.-value ps)]
      (set! (.-value ps) ps) x)))

(defn run [r n t]
  (let [ps (->Process n t r @r)]
    (add-watch r ps watch)
    (n) ps))
