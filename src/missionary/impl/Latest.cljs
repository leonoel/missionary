(ns ^:no-doc missionary.impl.Latest)

(declare transfer)

(deftype Process [combinator notifier terminator args inputs ^boolean race ^number alive]
  IFn
  (-invoke [_]
    (dotimes [i (alength inputs)]
      ((aget inputs i))))
  IDeref
  (-deref [p] (transfer p)))

(defn transfer [^Process ps]
  (let [args (.-args ps)
        inputs (.-inputs ps)
        c (.-combinator ps)
        x (try (when (nil? c) (throw (js/Error. "Undefined continuous flow.")))
               (dotimes [i (alength inputs)]
                 (when (identical? (aget args i) args)
                   (let [input (aget inputs i)]
                     (loop []
                       (aset args i nil)
                       (let [x @input]
                         (if (identical? (aget args i) args)
                           (recur) (aset args i x)))))))
               (.apply c nil args)
               (catch :default e
                 (set! (.-notifier ps) nil)
                 (dotimes [i (alength inputs)]
                   (let [it (aget inputs i)]
                     (it)
                     (if (identical? (aget args i) args)
                       (try @it (catch :default _))
                       (aset args i args)))) e))]
    (set! (.-race ps) true)
    (when (zero? (.-alive ps))
      ((.-terminator ps)))
    (if (nil? (.-notifier ps))
      (throw x) x)))

(defn dirty [^Process p ^number i]
  (let [args (.-args p)]
    (if (identical? (aget args i) args)
      (try @(aget (.-inputs p) i)
           (catch :default _))
      (do (aset args i args)
          (when (.-race p)
            (set! (.-race p) false)
            ((.-notifier p)))))))

(defn run [c fs n t]
  (let [it (iter fs)
        arity (count fs)
        args (object-array arity)
        inputs (object-array arity)
        ps (->Process c n t args inputs false arity)
        done #(when (zero? (set! (.-alive ps) (dec (.-alive ps))))
                (when (.-race ps) ((.-terminator ps))))]
    (dotimes [index arity]
      (aset inputs index ((.next it) #(dirty ps index) done))
      (when (nil? (aget args index)) (set! (.-combinator ps) nil)))
    (n) ps))