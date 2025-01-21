(ns missionary.impl.Propagator
  (:refer-clojure :exclude [time resolve])
  (:import missionary.Cancelled))

(declare lt sub unsub accept)

(deftype Publisher [ranks initp inits perform subscribe lcb rcb tick accept reject
                    ^boolean idle ^number children effect current]
  IFn
  (-invoke [this l r]
    (sub this l r))

  IComparable
  (-compare [this that]
    (if (identical? this that)
      0 (if (lt (.-ranks this) (.-ranks that))
          -1 +1))))

(deftype Process [parent state process waiting pending child sibling prop])

(deftype Subscription [source target lcb rcb prev next prop state ^boolean flag]
  IFn
  (-invoke [this]
    (unsub this))
  IDeref
  (-deref [this]
    (accept this)))

(deftype Context [^number time process sub cursor reacted delayed])

(def context (->Context 0 nil nil nil nil nil))

(def children 0)

(def ceiling (make-array 0))

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

(defn link [^Process x ^Process y]
  (if (lt (.-ranks (.-parent x)) (.-ranks (.-parent y)))
    (do (set! (.-sibling y) (.-child x))
        (set! (.-child x) y) x)
    (do (set! (.-sibling x) (.-child y))
        (set! (.-child y) x) y)))

(defn dequeue [^Process ps]
  (let [head (.-child ps)]
    (set! (.-child ps) ps)
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

(defn enqueue [^Process r ^Process p]
  (set! (.-child p) nil)
  (if (nil? r) p (link p r)))

(defn enter [pub]
  (let [top (.-idle pub)]
    (set! (.-idle pub) false)
    top))

(defn propagate [^Context ctx]
  (let [ps (.-process ctx)
        pub (.-parent ps)
        sub (.-prop ps)]
    (set! (.-prop ps) nil)
    (set! (.-idle pub) true)
    (set! (.-sub ctx) nil)
    (if (nil? (.-accept pub))
      (loop [sub sub]
        (when-not (nil? sub)
          (let [n (.-prop sub)]
            (set! (.-prop sub) nil)
            (set! (.-process ctx) (.-source sub))
            ((if (.-flag sub) (.-lcb sub) (.-rcb sub)) (.-state sub))
            (recur n))))
      (loop [sub sub]
        (when-not (nil? sub)
          (let [n (.-prop sub)]
            (set! (.-prop sub) nil)
            (set! (.-process ctx) (.-source sub))
            ((if (.-flag sub) (.-lcb sub) (.-rcb sub)))
            (recur n)))))))

(defn exit [^Context ctx ^boolean top ^boolean idle ^Process p ^Subscription s]
  (when top (propagate ctx))
  (if idle
    (do (loop []
          (when-some [ps (.-delayed ctx)]
            (set! (.-delayed ctx) nil)
            (loop [ps ps]
              (set! (.-reacted ctx) (dequeue ps))
              (let [pub (.-parent ps)]
                (when (identical? ps (.-current pub))
                  (set! (.-process ctx) ps)
                  (set! (.-cursor ctx) (.-ranks pub))
                  (set! (.-idle pub) false)
                  ((.-tick pub))
                  (propagate ctx)))
              (when-some [ps (.-reacted ctx)]
                (recur ps)))
            (set! (.-time ctx) (inc (.-time ctx)))
            (recur)))
        (set! (.-process ctx) nil)
        (set! (.-cursor ctx) nil))
    (do (set! (.-sub ctx) s)
        (set! (.-process ctx) p))))

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
    (set! (.-prop s) (.-prop ps))
    (set! (.-prop ps) s)))

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

(defn touch [^Context ctx]
  (if (nil? (.-cursor ctx))
    (do (set! (.-cursor ctx) ceiling)
        true) false))

(defn accept [^Subscription sub]
  (let [ctx context
        ps (.-target sub)
        pub (.-parent ps)
        top (enter pub)
        idle (touch ctx)
        p (.-process ctx)
        s (.-sub ctx)]
    (try (set! (.-process ctx) ps)
         (set! (.-sub ctx) sub)
         (set! (.-flag sub) false)
         (if (nil? (.-next sub))
           (do (set! (.-prop sub) (.-prop ps))
               (set! (.-prop ps) sub)
               (throw (Cancelled. "Flow publisher cancelled.")))
           (do (detach sub)
               (attach (.-waiting ps) (set! (.-waiting ps) sub))
               ((.-accept pub))))
         (finally (exit ctx top idle p s)))))

(defn cancel [^Process ps]
  (let [pub (.-parent ps)]
    (set! (.-current pub) nil)
    ((.-process ps))
    (when-not (identical? ps (.-child ps))
      ((.-tick pub)))))

(defn unsub [^Subscription sub]
  (let [ctx context
        ps (.-target sub)
        pub (.-parent ps)
        top (enter pub)
        idle (touch ctx)
        p (.-process ctx)
        s (.-sub ctx)]
    (try (set! (.-process ctx) ps)
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
         nil (finally (exit ctx top idle p s)))))

(defn bind [^Process ps f]
  (fn
    ([]
     (let [ctx context
           top (enter (.-parent ps))
           idle (touch ctx)
           p (.-process ctx)
           s (.-sub ctx)]
       (try (set! (.-process ctx) ps)
            (set! (.-sub ctx) nil)
            (f) (finally (exit ctx top idle p s)))))
    ([x]
     (let [ctx context
           top (enter (.-parent ps))
           idle (touch ctx)
           p (.-process ctx)
           s (.-sub ctx)]
       (try (set! (.-process ctx) ps)
            (set! (.-sub ctx) nil)
            (f x) (finally (exit ctx top idle p s)))))))

(defn sub [^Publisher pub lcb rcb]
  (let [ctx context
        top (enter pub)
        idle (touch ctx)
        p (.-process ctx)
        s (.-sub ctx)]
    (try (let [ps (if-some [ps (.-current pub)]
                    (set! (.-process ctx) ps)
                    (let [ps (->Process pub (.-initp pub) nil nil nil nil nil nil)]
                      (set! (.-child ps) ps)
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
           ((.-subscribe pub)) sub)
         (finally (exit ctx top idle p s)))))

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
        pub (.-parent ps)
        cursor (.-cursor ctx)]
    (if (or (nil? (.-process ps)) (not (identical? ps (.-current pub))))
      ((.-tick pub))
      (if (lt cursor (.-ranks pub))
        (set! (.-reacted ctx) (enqueue (.-reacted ctx) ps))
        (set! (.-delayed ctx) (enqueue (.-delayed ctx) ps))))))

(defn resolve []
  (let [ps (.-process context)
        pub (.-parent ps)]
    (when (identical? ps (.-current pub))
      (set! (.-effect pub) nil))))

(defn task [initp inits perform subscribe success failure tick task]
  (->Publisher (ranks) initp inits perform subscribe success failure tick nil nil true 0 task nil))

(defn flow [initp inits perform subscribe step done tick accept reject flow]
  (->Publisher (ranks) initp inits perform subscribe step done tick accept reject true 0 flow nil))