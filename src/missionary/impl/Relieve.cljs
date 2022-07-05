(ns ^:no-doc missionary.impl.Relieve)

(declare transfer)
(deftype Process [reducer notifier terminator iterator current ^boolean busy ^boolean done]
  IFn
  (-invoke [_] (iterator))
  IDeref
  (-deref [p] (transfer p)))

(defn transfer [^Process ps]
  (let [x (.-current ps)]
    (set! (.-current ps) ps)
    (when (nil? (.-reducer ps))
      ((.-terminator ps)))
    (if (nil? (.-notifier ps))
      (throw x) x)))

(defn ready [^Process ps]
  (loop [cb nil]
    (if (set! (.-busy ps) (not (.-busy ps)))
      (recur (if (.-done ps)
               (do (set! (.-reducer ps) nil)
                   (if (identical? ps (.-current ps))
                     (.-terminator ps) cb))
               (if-some [n (.-notifier ps)]
                 (let [r (.-current ps)]
                   (try (let [x @(.-iterator ps)]
                          (set! (.-current ps)
                            (if (identical? r ps)
                              x ((.-reducer ps) r x))))
                        (catch :default e
                          (set! (.-current ps) e)
                          (set! (.-notifier ps) nil)
                          ((.-iterator ps))))
                   (if (identical? r ps) n cb))
                 (do (try @(.-iterator ps) (catch :default _)) cb)))) cb)))

(defn run [rf f n t]
  (let [ps (->Process rf n t nil nil true false)]
    (set! (.-current ps) ps)
    (set! (.-iterator ps)
      (f #(when-some [cb (ready ps)] (cb))
        #(do (set! (.-done ps) true)
             (when-some [cb (ready ps)] (cb)))))
    (when-some [cb (ready ps)] (cb)) ps))