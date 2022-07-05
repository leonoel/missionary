(ns ^:no-doc missionary.impl.Sample)

(declare transfer)

(deftype Process [combinator notifier terminator args inputs
                  ^boolean busy ^boolean done ^number alive]
  IFn
  (-invoke [_] ((aget inputs (dec (alength inputs)))))
  IDeref
  (-deref [p] (transfer p)))

(defn ready [^Process ps]
  (let [args (.-args ps)
        inputs (.-inputs ps)
        sampled (dec (alength inputs))]
    (loop [cb nil]
      (if (set! (.-busy ps) (not (.-busy ps)))
        (if (.-done ps)
          (do (dotimes [i sampled]
                (let [input (aget inputs i)]
                  (input)
                  (if (identical? (aget args i) args)
                    (try @input (catch :default _))
                    (aset args i args))))
              (when (zero? (set! (.-alive ps) (dec (.-alive ps))))
                (.-terminator ps)))
          (if (identical? (aget args sampled) args)
            (do (try @(aget inputs sampled)
                     (catch :default _)) (recur cb))
            (.-notifier ps))) cb))))

(defn transfer [^Process ps]
  (let [c (.-combinator ps)
        args (.-args ps)
        inputs (.-inputs ps)
        sampled (dec (alength inputs))
        sampler (aget inputs sampled)
        x (try
            (try
              (when (nil? c) (throw (js/Error. "Undefined continuous flow.")))
              (dotimes [i sampled]
                (when (identical? (aget args i) args)
                  (let [input (aget inputs i)]
                    (loop []
                      (aset args i nil)
                      (let [x @input]
                        (if (identical? (aget args i) args)
                          (recur) (aset args i x)))))))
              (catch :default e
                (try @sampler (catch :default _))
                (throw e)))
            (aset args sampled @sampler)
            (.apply c nil args)
            (catch :default e
              (set! (.-notifier ps) nil)
              (sampler)
              (aset args sampled args) e))]
    (when-some [cb (ready ps)] (cb))
    (if (nil? (.-notifier ps))
      (throw x) x)))

(defn dirty [^Process p ^number i]
  (let [args (.-args p)]
    (if (identical? (aget args i) args)
      (try @(aget (.-inputs p) i)
           (catch :default _))
      (aset args i args))))

(defn run [c f fs n t]
  (let [it (iter fs)
        arity (inc (count fs))
        args (object-array arity)
        inputs (object-array arity)
        ps (->Process c n t args inputs false false arity)
        done #(when (zero? (set! (.-alive ps) (dec (.-alive ps))))
                ((.-terminator ps)))]
    (loop [index 0 flow f]
      (if (.hasNext it)
        (do (aset inputs index (flow #(dirty ps index) done))
            (when (nil? (aget args index)) (set! (.-combinator ps) nil))
            (recur (inc index) (.next it)))
        (aset inputs index
          (flow #(when-some [cb (ready ps)] (cb))
            #(do (set! (.-done ps) true)
                 (when-some [cb (ready ps)]
                   (cb))))))) ps))