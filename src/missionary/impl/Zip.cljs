(ns missionary.impl.Zip)

(declare cancel transfer)
(deftype Process
  [combinator notifier flusher
   iterators results
   ^number pending]
  IFn
  (-invoke [z] (cancel z))
  IDeref
  (-deref [z] (transfer z)))

(defn cancel [^Process z]
  (let [its (.-iterators z)]
    (dotimes [i (alength its)]
      (when-some [it (aget its i)] (it)))))

(defn transfer [^Process z]
  (let [its (.-iterators z)
        res (.-results z)]
    (try (set! (.-pending z) (dec (.-pending z)))
         (dotimes [i (alength its)]
           (set! (.-pending z) (inc (.-pending z)))
           (aset res i @(aget its i)))
         (.apply (.-combinator z) nil res)
         (catch :default e
           (set! (.-notifier z) (.-flusher z))
           (throw e))
         (finally
           (set! (.-pending z) (inc (.-pending z)))
           (when (zero? (.-pending z)) ((.-notifier z)))
           (when (identical? (.-notifier z) (.-flusher z)) (cancel z))))))

(defn run [f fs n t]
  (let [c (count fs)
        i (iter fs)
        z (->Process f n nil (object-array c) (object-array c) 0)]
    (set! (.-flusher z)
      #(let [its (.-iterators z)
             cnt (alength its)]
         (loop []
           (let [flushed (loop [i 0
                                f 0]
                           (if (< i cnt)
                             (recur
                               (inc i)
                               (if-some [it (aget its i)]
                                 (do (try @it (catch :default _))
                                     (inc f)) f)) f))]
             (if (zero? flushed)
               (t) (when (zero? (set! (.-pending z) (+ (.-pending z) flushed)))
                     (recur)))))))
    (loop [index 0]
      (aset (.-iterators z) index
        ((.next i)
         #(let [p (dec (.-pending z))]
            (set! (.-pending z) p)
            (when (zero? p) ((.-notifier z))))
         #(do (aset (.-iterators z) index nil)
              (set! (.-notifier z) (.-flusher z))
              (let [p (set! (.-pending z) (dec (.-pending z)))]
                (when-not (neg? p)
                  (cancel z)
                  (when (zero? p) ((.-notifier z))))))))
      (when (.hasNext i) (recur (inc index))))
    (when (zero? (set! (.-pending z) (+ (.-pending z) c)))
      ((.-notifier z))) z))
