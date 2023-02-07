(ns missionary.impl.Propagator)

(declare context enter exit lt none)

(deftype Subscription [lcb rcb pub sub ^boolean flag value state prev next]
  IFn
  (-invoke [this]
    (let [ctx context
          ps (.-pub this)
          pub (.-parent ps)
          held (enter pub)
          busy (.-busy ctx)
          node (.-node ctx)
          edge (.-edge ctx)]
      (set! (.-busy ctx) true)
      (set! (.-node ctx) ps)
      (set! (.-edge ctx) this)
      ((.-state this)) (exit pub ctx held busy node edge)))
  IDeref
  (-deref [this]
    (let [ctx context
          ps (.-pub this)
          pub (.-parent ps)
          held (enter pub)
          busy (.-busy ctx)
          node (.-node ctx)
          edge (.-edge ctx)]
      (set! (.-busy ctx) true)
      (set! (.-node ctx) ps)
      (set! (.-edge ctx) this)
      (try @(.-state this)
           (finally (exit pub ctx held busy node edge))))))

(deftype Publisher [ranks held children current prop child sibling]
  IFn
  (-invoke [this lcb rcb]
    (let [ctx context
          held (enter this)
          busy (.-busy ctx)
          node (.-node ctx)
          edge (.-edge ctx)
          ps (.-current this)
          s (->Subscription lcb rcb ps node false nil nil nil nil)]
      (set! (.-busy ctx) true)
      (set! (.-node ctx) ps)
      (set! (.-edge ctx) s)
      (set! (.-state s) @(.-state ps))
      (exit this ctx held busy node edge) s))

  IComparable
  (-compare [this that]
    (if (identical? this that)
      0 (if (lt (.-ranks this) (.-ranks that))
          -1 +1))))

(deftype Process [parent state subs])

(deftype Context [^number step ^boolean busy node edge emitter reacted delayed])

(def context (->Context 0 false nil nil nil nil nil))

(def children 0)

(defn ^boolean lt [x y]
  (let [xl (alength x)
        yl (alength y)
        ml (min xl yl)]
    (loop [i 0]
      (if (< i ml)
        (let [xi (aget x i)
              yi (aget y i)]
          (if (== xi yi)
            (recur (inc i))
            (< xi yi)))
        (> xl yl)))))

(defn link [^Publisher x ^Publisher y]
  (if (lt (.-ranks x) (.-ranks y))
    (do (set! (.-sibling y) (.-child x))
        (set! (.-child x) y) x)
    (do (set! (.-sibling x) (.-child y))
        (set! (.-child y) x) y)))

(defn dequeue [^Publisher pub]
  (let [head (.-child pub)]
    (set! (.-child pub) nil)
    (loop [heap nil
           prev nil
           head head]
      (if (nil? head)
        (if (nil? prev) heap (if (nil? heap) prev (link heap prev)))
        (let [next (.-sibling head)]
          (set! (.-sibling head) nil)
          (if (nil? prev)
            (recur heap head next)
            (let [head (link prev head)]
              (recur (if (nil? heap) head (link heap head)) nil next))))))))

(defn enqueue [^Publisher r ^Publisher p]
  (if (nil? r) p (link p r)))

(defn enter [pub]
  (let [held (.-held pub)]
    (set! (.-held pub) true)
    held))

(defn flip [ctx]
  (let [pub (.-delayed ctx)]
    (set! (.-delayed ctx) nil)
    (set! (.-step ctx) (inc (.-step ctx)))
    pub))

(defn callback [ctx s]
  (set! (.-edge ctx) nil)
  (loop [s s]
    (when-not (nil? s)
      (let [cb (if (.-flag s) (.-rcb s) (.-lcb s))
            value (.-value s)
            n (.-next s)]
        (set! (.-value s) nil)
        (set! (.-next s) nil)
        (set! (.-node ctx) (.-sub s))
        (if (identical? none value)
          (cb) (cb value))
        (recur n)))))

(defn tick [^Publisher pub ^Context ctx]
  (set! (.-held pub) true)
  (let [ps (.-current pub)]
    (set! (.-reacted ctx) (dequeue pub))
    (set! (.-emitter ctx) pub)
    (set! (.-node ctx) ps)
    ((.-state ps)))
  (let [s (.-prop pub)]
    (set! (.-prop pub) nil)
    (set! (.-held pub) false)
    (callback ctx s)))

(defn exit [^Publisher pub ^Context ctx ^boolean held ^boolean busy ^Process node ^Subscription edge]
  (let [s (when-not held
            (let [s (.-prop pub)]
              (set! (.-prop pub) nil) s))]
    (set! (.-held pub) held)
    (callback ctx s)
    (when-not busy
      (set! (.-edge ctx) nil)
      (loop []
        (if-some [pub (.-reacted ctx)]
          (do (tick pub ctx) (recur))
          (do (set! (.-step ctx) (inc (.-step ctx)))
              (when-some [pub (.-delayed ctx)]
                (set! (.-delayed ctx) nil)
                (tick pub ctx) (recur)))))
      (set! (.-emitter ctx) nil))
    (set! (.-busy ctx) busy)
    (set! (.-node ctx) node)
    (set! (.-edge ctx) edge)
    nil))

(def none (js-obj))

(defn step [] (.-step context))

(defn reset [state]
  (let [pub (.-parent (.-node context))]
    (set! (.-current pub) (->Process pub state nil))))

(defn attached []
  (some? (.-prev (.-edge context))))

(defn detach [flag value]
  (let [ctx context
        ps (.-node ctx)
        s (.-edge ctx)
        p (.-prev s)
        n (.-next s)
        pub (.-parent ps)]
    (set! (.-value s) value)
    (set! (.-flag s) flag)
    (set! (.-prev s) nil)
    (set! (.-next s) (.-prop pub))
    (set! (.-prop pub) s)
    (if (identical? p s)
      (set! (.-subs ps) nil)
      (do (set! (.-prev n) p)
          (set! (.-next p) n)
          (set! (.-subs ps) n)))))

(defn dispatch [flag value]
  (let [ctx context
        ps (.-node ctx)
        pub (.-parent ps)]
    (when-some [subs (.-subs ps)]
      (set! (.-subs ps) nil)
      (set! (.-next (loop [s subs]
                      (set! (.-value s) value)
                      (set! (.-flag s) flag)
                      (set! (.-prev s) nil)
                      (let [n (.-next s)]
                        (if (identical? n subs)
                          s (recur n))))))
      (set! (.-prop pub) subs))))

(defn schedule []
  (let [ctx context
        ps (.-node ctx)
        pub (.-parent ps)]
    (if (identical? ps (.-current pub))
      (let [emitter (.-emitter ctx)]
        (if (or (nil? emitter) (lt (.-ranks emitter) (.-ranks pub)))
          (set! (.-reacted ctx) (enqueue (.-reacted ctx) pub))
          (set! (.-delayed ctx) (enqueue (.-delayed ctx) pub))))
      ((.-state ps)))))

(defn attach []
  (let [ctx context
        ps (.-node ctx)
        s (.-edge ctx)]
    (if-some [n (.-subs ps)]
      (let [p (.-prev n)]
        (set! (.-next s) n)
        (set! (.-prev s) p)
        (set! (.-next p) s)
        (set! (.-prev n) s))
      (do (set! (.-subs ps) s)
          (set! (.-prev s) s)
          (set! (.-next s) s)))))

(defn bind [f]
  (let [ps (.-node context)]
    (fn
      ([]
       (let [ctx context
             pub (.-parent ps)
             held (enter pub)
             busy (.-busy ctx)
             node (.-node ctx)
             edge (.-edge ctx)]
         (set! (.-busy ctx) true)
         (set! (.-node ctx) ps)
         (set! (.-edge ctx) nil)
         (f)
         (exit pub ctx held busy node edge)))
      ([x]
       (let [ctx context
             pub (.-parent ps)
             held (enter pub)
             busy (.-busy ctx)
             node (.-node ctx)
             edge (.-edge ctx)]
         (set! (.-busy ctx) true)
         (set! (.-node ctx) ps)
         (set! (.-edge ctx) nil)
         (f x)
         (exit pub ctx held busy node edge))))))

(defn publisher [state]
  (let [pub (->Publisher
              (if-some [^Process ps (.-node context)]
                (let [p (.-parent ps)
                      r (.-ranks p)
                      n (alength r)
                      a (make-array (inc n))]
                  (dotimes [i n] (aset a i (aget r i)))
                  (doto a (aset n (doto (.-children p) (->> (inc) (set! (.-children p)))))))
                (doto (make-array 1) (aset 0 (doto children (->> (inc) (set! children))))))
              false 0 nil nil nil nil)]
    (set! (.-current pub) (->Process pub state nil)) pub))