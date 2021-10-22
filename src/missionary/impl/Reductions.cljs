(ns missionary.impl.Reductions)

(declare transfer)
(deftype Process
  [reducer notifier terminator result input
   ^boolean busy
   ^boolean done]
  IFn
  (-invoke [_] (input))
  IDeref
  (-deref [p] (transfer p)))

(defn ready [^Process p]
  (loop []
    (when (set! (.-busy p) (not (.-busy p)))
      (if (.-done p)
        ((.-terminator p))
        (if (nil? (.-reducer p))
          (do (try @(.-input p) (catch :default _))
              (recur)) ((.-notifier p)))))))

(defn transfer [^Process p]
  (try
    (let [f (.-reducer p)
          r (.-result p)
          r (if (identical? r p)
              (f) (f r @(.-input p)))]
      (set! (.-result p)
        (if (reduced? r)
          (do ((.-input p)) (set! (.-reducer p) nil) @r) r)))
    (catch :default e
      ((.-input p)) (set! (.-reducer p) nil) (throw e))
    (finally (ready p))))

(defn run [rf f n t]
  (let [p (->Process rf n t nil nil true false)]
    (set! (.-result p) p)
    (set! (.-input p)
      (f #(ready p)
        #(do (set! (.-done p) true) (ready p))))
    (n) p))