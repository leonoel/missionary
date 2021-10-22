(ns missionary.impl.Reduce)

(deftype Process
  [reducer status failure result input
   ^boolean busy
   ^boolean done]
  IFn
  (-invoke [_] (input)))

(defn transfer [^Process p]
  (set! (.-result p)
    (try
      (let [f (.-reducer p)
            r (.-result p)
            r (if (identical? r p)
                (f) (f r @(.-input p)))]
        (if (reduced? r)
          (do ((.-input p)) (set! (.-reducer p) nil) @r) r))
      (catch :default e
        ((.-input p)) (set! (.-reducer p) nil)
        (set! (.-status p) (.-failure p)) e))))

(defn ready [^Process p]
  (loop []
    (when (set! (.-busy p) (not (.-busy p)))
      (if (.-done p)
        ((.-status p) (.-result p))
        (do (if (nil? (.-reducer p))
              (try @(.-input p) (catch :default _))
              (transfer p)) (recur))))))

(defn run [rf flow success failure]
  (let [p (->Process rf success failure nil nil true false)]
    (set! (.-result p) p)
    (set! (.-input p)
      (flow #(ready p)
        #(do (set! (.-done p) true) (ready p))))
    (transfer p)
    (ready p) p))