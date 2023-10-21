(ns ^:no-doc missionary.impl.Observe
  (:import missionary.Cancelled))

(declare kill transfer)

(deftype Process [notifier terminator unsub value]
  IFn
  (-invoke [this] (kill this) nil)
  IDeref
  (-deref [this] (transfer this)))

(defn kill [^Process ps]
  (when-some [cb (.-notifier ps)]
    (set! (.-notifier ps) nil)
    (let [x (.-value ps)]
      (set! (.-value ps) nil)
      (when (identical? x ps) (cb)))))

(defn transfer [^Process ps]
  (if (nil? (.-notifier ps))
    (do ((.-terminator ps))
        ((.-unsub ps))
        (throw (Cancelled. "Observe cancelled.")))
    (let [x (.-value ps)]
      (set! (.-value ps) ps) x)))

(defn run [s n t]
  (let [ps (->Process n t nil nil)]
    (set! (.-value ps) ps)
    (try (set! (.-unsub ps)
           (s (fn [x]
                (when-some [cb (.-notifier ps)]
                  (if (identical? ps (.-value ps))
                    (do (set! (.-value ps) x) (cb))
                    (throw (js/Error. "Can't process event - consumer is not ready.")))))))
         (catch :default e
           (set! (.-notifier ps) nil)
           (set! (.-unsub ps) #(throw e))
           (if (identical? ps (.-value ps))
             (n) (set! (.-value ps) ps)))) ps))

