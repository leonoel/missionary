(ns missionary.impl.Latest)

(declare transfer)

(deftype Process [combinator notifier terminator args inputs
                  ^boolean race ^number alive]
  IFn
  (-invoke [_]
    (dotimes [i (alength inputs)]
      ((aget inputs i))))
  IDeref
  (-deref [p] (transfer p)))

(defn transfer [^Process p]
  (let [args (.-args p)
        inputs (.-inputs p)
        c (.-combinator p)]
    (try (when (nil? c) (throw (js/Error. "Undefined continuous flow.")))
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
           (dotimes [i (alength inputs)]
             (let [it (aget inputs i)]
               (it)
               (if (identical? (aget args i) args)
                 (try @it (catch :default _))
                 (aset args i args))))
           (throw e))
         (finally
           (set! (.-race p) true)
           (when (zero? (.-alive p))
             ((.-terminator p)))))))

(defn dirty [^Process p ^number i]
  (let [args (.-args p)]
    (if (identical? (aget args i) args)
      (try @(aget (.-inputs p) i)
           (catch :default _))
      (do (aset args i args)
          (when (.-race p)
            (set! (.-race p) false)
            ((.-notifier p)))))))

(defn terminate [^Process p]
  (when (zero? (set! (.-alive p) (dec (.-alive p))))
    (when (.-race p) ((.-terminator p)))))

(defn run [c fs n t]
  (let [it (iter fs)
        arity (count fs)
        args (object-array arity)
        inputs (object-array arity)
        process (->Process c n t args inputs false arity)
        done #(terminate process)]
    (dotimes [index arity]
      (aset inputs index ((.next it) #(dirty process index) done))
      (when (nil? (aget args index)) (set! (.-combinator process) nil)))
    (n) process))