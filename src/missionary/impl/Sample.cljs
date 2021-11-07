(ns missionary.impl.Sample)

(declare transfer)

(deftype Process [combinator notifier terminator args inputs
                  ^boolean busy ^boolean done ^number alive]
  IFn
  (-invoke [_] ((aget inputs (dec (alength inputs)))))
  IDeref
  (-deref [p] (transfer p)))

(defn terminate [^Process p]
  (when (zero? (set! (.-alive p) (dec (.-alive p))))
    ((.-terminator p))))

(defn ready [^Process p]
  (let [args (.-args p)
        inputs (.-inputs p)
        sampled (dec (alength inputs))]
    (loop []
      (when (set! (.-busy p) (not (.-busy p)))
        (if (.-done p)
          (do (dotimes [i sampled]
                (let [input (aget inputs i)]
                  (input)
                  (if (identical? (aget args i) args)
                    (try @input (catch :default _))
                    (aset args i args))))
              (terminate p))
          (if (identical? (aget args sampled) args)
            (do (try @(aget inputs sampled)
                     (catch :default _)) (recur))
            ((.-notifier p))))))))

(defn transfer [^Process p]
  (let [c (.-combinator p)
        args (.-args p)
        inputs (.-inputs p)
        sampled (dec (alength inputs))
        sampler (aget inputs sampled)]
    (try
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
        (sampler)
        (aset args sampled args)
        (throw e))
      (finally (ready p)))))

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
        process (->Process c n t args inputs false false arity)
        done #(terminate process)]
    (loop [index 0 flow f]
      (if (.hasNext it)
        (do (aset inputs index (flow #(dirty process index) done))
            (when (nil? (aget args index)) (set! (.-combinator process) nil))
            (recur (inc index) (.next it)))
        (aset inputs index
          (flow #(ready process)
            #(do (set! (.-done process) true)
                 (ready process)))))) process))