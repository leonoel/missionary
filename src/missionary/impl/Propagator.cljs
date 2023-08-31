(ns missionary.impl.Propagator
  (:refer-clojure :exclude [time resolve])
  (:import missionary.Cancelled))

(declare lt sub unsub accept)

(deftype Publisher [ranks initp inits perform subscribe lcb rcb tick accept reject
                    ^boolean held ^number children effect current child sibling prop]
  IFn
  (-invoke [this l r]
    (sub this l r))

  IComparable
  (-compare [this that]
    (if (identical? this that)
      0 (if (lt (.-ranks this) (.-ranks that))
          -1 +1))))

(deftype Process [parent state process waiting pending])

(deftype Subscription [source target lcb rcb prev next prop state ^boolean flag]
  IFn
  (-invoke [this]
    (unsub this))
  IDeref
  (-deref [this]
    (accept this)))

(deftype Context [^number time ^boolean busy process sub emitter reacted delayed])

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

(defn cancel [^Process ps]
  (set! (.-current (.-parent ps)) nil)
  ((.-process ps)))

(defn propagate [^Context ctx ^Process ps ^Subscription sub]
  (let [pub (.-parent ps)]
    (set! (.-sub ctx) nil)
    (loop [sub sub]
      (when-not (nil? sub)
        (let [cb (if (.-flag sub) (.-lcb sub) (.-rcb sub))
              n (.-prop sub)]
          (set! (.-prop sub) nil)
          (set! (.-process ctx) (.-source sub))
          (if (nil? (.-accept pub)) (cb (.-state sub)) (cb))
          (recur n))))))

(defn tick [^Publisher pub ^Context ctx]
  (set! (.-held pub) true)
  (let [ps (.-current pub)]
    (set! (.-reacted ctx) (dequeue pub))
    (set! (.-emitter ctx) pub)
    (set! (.-process ctx) ps)
    ((.-tick pub))
    (let [sub (.-prop pub)]
      (set! (.-prop pub) nil)
      (set! (.-held pub) false)
      (propagate ctx ps sub))))

(defn exit [^Context ctx ^boolean held ^boolean b ^Process p ^Subscription s]
  (let [ps (.-process ctx)
        pub (.-parent ps)
        sub (when-not held
              (let [sub (.-prop pub)]
                (set! (.-prop pub) nil) sub))]
    (set! (.-held pub) held)
    (propagate ctx ps sub)
    (when-not b
      (set! (.-sub ctx) nil)
      (loop []
        (if-some [pub (.-reacted ctx)]
          (do (tick pub ctx) (recur))
          (do (set! (.-time ctx) (inc (.-time ctx)))
              (when-some [pub (.-delayed ctx)]
                (set! (.-delayed ctx) nil)
                (tick pub ctx) (recur)))))
      (set! (.-emitter ctx) nil))
    (set! (.-busy ctx) b)
    (set! (.-process ctx) p)
    (set! (.-sub ctx) s)))

(defn attach [^Subscription n ^Subscription s]
  (if (nil? n)
    (do (set! (.-prev s) s)
        (set! (.-next s) s))
    (let [p (.-prev n)]
      (set! (.-next s) n)
      (set! (.-prev s) p)
      (set! (.-next p) s)
      (set! (.-prev n) s))))

(defn dispatch [^Subscription s]
  (let [ps (.-target s)
        p (.-prev s)
        n (.-next s)]
    (set! (.-prev s) nil)
    (set! (.-next s) nil)
    (if (identical? p s)
      (set! (.-waiting ps) nil)
      (do (set! (.-prev n) p)
          (set! (.-next p) n)
          (set! (.-waiting ps) n)))
    (let [pub (.-parent ps)]
      (set! (.-prop s) (.-prop pub))
      (set! (.-prop pub) s))))

(defn detach [^Subscription s]
  (let [ps (.-target s)
        p (.-prev s)
        n (.-next s)]
    (set! (.-prev s) nil)
    (set! (.-next s) nil)
    (if (identical? p s)
      (set! (.-pending ps) nil)
      (do (set! (.-prev n) p)
          (set! (.-next p) n)
          (set! (.-pending ps) n)))))

(defn foreach [^Context ctx ^Subscription subs f]
  (when-not (nil? subs)
    (let [s (.-sub ctx)]
      (loop [sub (.-next subs)]
        (let [n (.-next sub)]
          (set! (.-sub ctx) sub) (f)
          (when-not (identical? sub subs)
            (recur n))))
      (set! (.-sub ctx) s))))

(defn accept [^Subscription sub]
  (let [ctx context
        ps (.-target sub)
        pub (.-parent ps)
        held (enter pub)
        b (.-busy ctx)
        p (.-process ctx)
        s (.-sub ctx)]
    (try (set! (.-busy ctx) true)
         (set! (.-process ctx) ps)
         (set! (.-sub ctx) sub)
         (set! (.-flag sub) false)
         (if (nil? (.-next sub))
           (do (set! (.-prop sub) (.-prop pub))
               (set! (.-prop pub) sub)
               (throw (Cancelled. "Flow publisher cancelled.")))
           (do (detach sub)
               (attach (.-waiting ps) (set! (.-waiting ps) sub))
               ((.-accept pub))))
         (finally (exit ctx held b p s)))))

(defn unsub [^Subscription sub]
  (let [ctx context
        ps (.-target sub)
        pub (.-parent ps)
        held (enter pub)
        b (.-busy ctx)
        p (.-process ctx)
        s (.-sub ctx)]
    (try (set! (.-busy ctx) true)
         (set! (.-process ctx) ps)
         (set! (.-sub ctx) sub)
         (when-not (nil? (.-next sub))
           (when-not (nil? (.-effect pub))
             (when (identical? ps (.-current pub))
               (if (nil? (.-accept pub))
                 (if (identical? sub (.-next sub))
                   (cancel ps)
                   (do (set! (.-state sub) (Cancelled. "Task publisher cancelled."))
                       (dispatch sub)))
                 (if (.-flag sub)
                   (if (and (identical? sub (.-next sub)) (nil? (.-waiting ps)))
                     (cancel ps)
                     (do (detach sub)
                         ((.-reject pub))))
                   (if (and (identical? sub (.-next sub)) (nil? (.-pending ps)))
                     (cancel ps)
                     (do (set! (.-flag sub) true)
                         (dispatch sub))))))))
         nil (finally (exit ctx held b p s)))))

(defn bind [^Process ps f]
  (fn
    ([]
     (let [ctx context
           held (enter (.-parent ps))
           b (.-busy ctx)
           p (.-process ctx)
           s (.-sub ctx)]
       (try
         (set! (.-busy ctx) true)
         (set! (.-process ctx) ps)
         (set! (.-sub ctx) nil)
         (f) (finally (exit ctx held b p s)))))
    ([x]
     (let [ctx context
           held (enter (.-parent ps))
           b (.-busy ctx)
           p (.-process ctx)
           s (.-sub ctx)]
       (try
         (set! (.-busy ctx) true)
         (set! (.-process ctx) ps)
         (set! (.-sub ctx) nil)
         (f x) (finally (exit ctx held b p s)))))))

(defn sub [^Publisher pub lcb rcb]
  (let [ctx context
        held (enter pub)
        b (.-busy ctx)
        p (.-process ctx)
        s (.-sub ctx)]
    (try (set! (.-busy ctx) true)
         (let [ps (if-some [ps (.-current pub)]
                    (set! (.-process ctx) ps)
                    (let [ps (->Process pub (.-initp pub) nil nil nil)]
                      (set! (.-current pub) ps)
                      (set! (.-process ctx) ps)
                      (set! (.-sub ctx) nil)
                      ((.-perform pub))
                      (set! (.-process ps)
                        ((.-effect pub)
                         (bind ps (.-lcb pub))
                         (bind ps (.-rcb pub))))
                      ps))
               sub (->Subscription p ps lcb rcb nil nil nil (.-inits pub) false)]
           (attach (.-waiting ps) (set! (.-waiting ps) sub))
           (set! (.-sub ctx) sub)
           ((.-subscribe pub))
           sub) (finally (exit ctx held b p s)))))

(defn ranks []
  (if-some [^Process ps (.-process context)]
    (let [p (.-parent ps)
          r (.-ranks p)
          n (alength r)
          a (make-array (inc n))]
      (dotimes [i n] (aset a i (aget r i)))
      (doto a (aset n (doto (.-children p) (->> (inc) (set! (.-children p)))))))
    (doto (make-array 1) (aset 0 (doto children (->> (inc) (set! children)))))))

;; public API

(defn time []
  (.-time context))

(defn transfer []
  @(.-process (.-process context)))

(defn getp []
  (.-state (.-process context)))

(defn setp [x]
  (set! (.-state (.-process context)) x))

(defn gets []
  (.-state (.-sub context)))

(defn sets [x]
  (set! (.-state (.-sub context)) x))

(defn success [x]
  (let [sub (.-sub context)]
    (set! (.-flag sub) true)
    (set! (.-state sub) x)
    (dispatch sub)))

(defn failure [x]
  (let [sub (.-sub context)]
    (set! (.-state sub) x)
    (dispatch sub)))

(defn step []
  (let [sub (.-sub context)]
    (set! (.-flag sub) true)
    (dispatch sub)
    (let [ps (.-target sub)]
      (attach (.-pending ps)
        (set! (.-pending ps) sub)))))

(defn done []
  (let [sub (.-sub context)]
    (dispatch sub)))

(defn waiting [f]
  (let [ctx context]
    (foreach ctx (.-waiting (.-process ctx)) f)))

(defn pending [f]
  (let [ctx context]
    (foreach ctx (.-pending (.-process ctx)) f)))

(defn schedule []
  (let [ctx context
        ps (.-process ctx)
        pub (.-parent ps)]
    (if (and (identical? ps (.-current pub)) (some? (.-process ps)))
      (let [emitter (.-emitter ctx)]
        (if (or (nil? emitter) (lt (.-ranks emitter) (.-ranks pub)))
          (set! (.-reacted ctx) (enqueue (.-reacted ctx) pub))
          (set! (.-delayed ctx) (enqueue (.-delayed ctx) pub))))
      ((.-tick pub)))))

(defn resolve []
  (let [ps (.-process context)
        pub (.-parent ps)]
    (when (identical? ps (.-current pub))
      (set! (.-effect pub) nil))))

(defn task [initp inits perform subscribe success failure tick task]
  (->Publisher (ranks) initp inits perform subscribe success failure tick nil nil false 0 task nil nil nil nil))

(defn flow [initp inits perform subscribe step done tick accept reject flow]
  (->Publisher (ranks) initp inits perform subscribe step done tick accept reject false 0 flow nil nil nil nil))