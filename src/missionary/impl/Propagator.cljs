(ns missionary.impl.Propagator
  (:import missionary.Cancelled))

(declare sub unsub transfer)

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

(deftype Publisher [ranks strategy arg effect node current]
  IFn
  (-invoke [this lcb rcb]
    (sub this lcb rcb))

  IComparable
  (-compare [this that]
    (if (identical? this that)
      0 (if (lt (.-ranks this) (.-ranks that))
          -1 +1))))

(deftype Process [parent pressure owned input child sibling
                  ready pending failed dirty flag state])

(deftype Subscription [source target lcb rcb prev next ready state]
  IFn
  (-invoke [this]
    (unsub this))
  IDeref
  (-deref [this]
    (transfer this)))

(deftype Context [cursor process reacted delayed buffer])

(defprotocol Strategy
  (tick [_ ps])
  (publish [_ ps])
  (refresh [_ ps])
  (subscribe [_ sub idle])
  (unsubscribe [_ sub idle])
  (accept [_ sub idle])
  (reject [_ sub idle]))

(def context (->Context nil nil nil nil (make-array 1)))

(def ceiling (make-array 0))

(def root 0)

(defn acquire [_])

(defn release [_])

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

(defn schedule [^Process ps]
  (let [^Context ctx context
        ^Publisher pub (.-parent ps)]
    (if (lt (.-cursor ctx) (.-ranks pub))
      (set! (.-reacted ctx) (enqueue (.-reacted ctx) ps))
      (set! (.-delayed ctx) (enqueue (.-delayed ctx) ps)))))

(defn crash [^Process ps e]
  (let [^Publisher pub (.-parent ps)]
    (set! (.-state ps) e)
    (set! (.-failed ps) true)
    (when (identical? (.-current pub) ps)
      ((.-input ps)))))

(defn ^boolean ack [^Process ps]
  (zero? (set! (.-pressure ps) (dec (.-pressure ps)))))

(defn detach [^Subscription s]
  (let [^Subscription p (.-prev s)
        ^Subscription n (.-next s)]
    (set! (.-prev s) nil)
    (set! (.-next s) nil)
    (when-not (identical? p s)
      (set! (.-next p) n)
      (set! (.-prev n) p))))

(defn attach [^Subscription p ^Subscription s]
  (if (nil? p)
    (do (set! (.-prev s) s)
        (set! (.-next s) s))
    (let [^Subscription n (.-next p)]
      (set! (.-next p) s)
      (set! (.-prev s) p)
      (set! (.-next s) n)
      (set! (.-prev n) s))))

(defn union [^Subscription p ^Subscription s]
  (when-not (nil? p)
    (let [^Subscription n (.-next p)
          ^Subscription t (.-next s)]
      (set! (.-next p) t)
      (set! (.-prev t) p)
      (set! (.-next s) n)
      (set! (.-prev n) s))))

(defn clear [^Subscription head]
  (loop [^Subscription sub head]
    (let [n (.-next sub)]
      (set! (.-prev sub) nil)
      (set! (.-next sub) nil)
      (when-not (identical? n head)
        (recur n)))))

(defn bufferize [^Context ctx ^Subscription head]
  (loop [i 0
         ^Subscription sub head
         ^objects buf (.-buffer ctx)]
    (set! (.-ready sub) false)
    (aset buf i sub)
    (let [i (inc i)
          sub (.-next sub)
          cap (alength buf)
          buf (if (== i cap)
                (let [arr (make-array (bit-shift-left cap 1))]
                  (dotimes [i cap]
                    (aset arr i (aget buf i)))
                  arr) buf)]
      (if (identical? sub head)
        (set! (.-buffer ctx) buf)
        (recur i sub buf)))))

(defn terminate [^Context ctx ^Process ps]
  (let [^Publisher pub (.-parent ps)]
    (set! (.-input ps) nil)
    (when-some [^Subscription ready (.-ready ps)]
      (set! (.-ready ps) nil)
      (bufferize ctx ready)
      (clear ready))
    (release pub)))

(defn invalidate [^Context ctx ^Process ps]
  (let [^Publisher pub (.-parent ps)]
    (set! (.-dirty ps) true)
    (when-some [^Subscription ready (.-ready ps)]
      (set! (.-ready ps) nil)
      (bufferize ctx ready)
      (union (.-pending ps)
        (set! (.-pending ps) ready)))
    (release pub)))

(defn step-all [^Process ps]
  (let [^Context ctx context]
    (invalidate ctx ps)
    (let [^objects buf (.-buffer ctx)]
      (loop [i 0]
        (when-some [^Subscription sub (aget buf i)]
          (aset buf i nil)
          (set! (.-process ctx) (.-source sub))
          ((.-lcb sub))
          (recur (inc i)))))))

(defn done-all [^Process ps]
  (let [^Context ctx context]
    (terminate ctx ps)
    (let [^objects buf (.-buffer ctx)]
      (loop [i 0]
        (when-some [^Subscription sub (aget buf i)]
          (aset buf i nil)
          (set! (.-process ctx) (.-source sub))
          ((.-rcb sub))
          (recur (inc i)))))))

(defn success-all [^Process ps]
  (let [^Context ctx context]
    (terminate ctx ps)
    (let [^objects buf (.-buffer ctx)]
      (loop [i 0]
        (when-some [^Subscription sub (aget buf i)]
          (aset buf i nil)
          (set! (.-process ctx) (.-source sub))
          ((.-lcb sub) (.-state ps))
          (recur (inc i)))))))

(defn failure-all [^Process ps]
  (let [^Context ctx context]
    (terminate ctx ps)
    (let [^objects buf (.-buffer ctx)]
      (loop [i 0]
        (when-some [^Subscription sub (aget buf i)]
          (aset buf i nil)
          (set! (.-process ctx) (.-source sub))
          ((.-rcb sub) (.-state ps))
          (recur (inc i)))))))

(defn stream-ack [^Subscription sub]
  (let [^Process ps (.-target sub)]
    (when (nil? (set! (.-pending ps) (detach sub)))
      (when-not ^boolean (.-owned ps)
        (set! (.-state ps) ps)
        (when (ack ps) (schedule ps))))))

(defn stream-emit [^Process ps]
  (if ^boolean (.-flag ps)
    (done-all ps)
    (do (set! (.-owned ps) true)
        (schedule ps)
        (step-all ps))))

(defn signal-emit [^Process ps]
  (if ^boolean (.-flag ps)
    (done-all ps)
    (step-all ps)))

(defn failed-emit [^Process ps]
  (let [^Publisher pub (.-parent ps)]
    (loop []
      (if ^boolean (.-flag ps)
        (done-all ps)
        (do (try @(.-input ps) (catch :default _))
            (if (ack ps) (recur) (release pub)))))))

(defn leave [^Context ctx ^boolean idle]
  (when idle
    (loop []
      (when-some [ps (.-delayed ctx)]
        (set! (.-delayed ctx) nil)
        (loop [^Process ps ps]
          (let [^Publisher pub (.-parent ps)]
            (set! (.-cursor ctx) (.-ranks pub))
            (set! (.-reacted ctx) (dequeue ps))
            (acquire pub)
            (if ^boolean (.-failed ps)
              (if ^boolean (.-owned ps)
                (do (set! (.-owned ps) false)
                    (if (ack ps)
                      (failed-emit ps)
                      (release pub)))
                (failed-emit ps))
              (tick (.-strategy pub) ps))
            (when-some [ps (.-reacted ctx)]
              (recur ps))))
        (recur)))
    (set! (.-cursor ctx) nil)
    (set! (.-process ctx) nil)))

(defn ^boolean enter [^Context ctx]
  (if (nil? (.-cursor ctx))
    (do (set! (.-cursor ctx) ceiling)
        true) false))

(defn request [^Subscription sub idle]
  (let [^Process ps (.-target sub)
        ^Publisher pub (.-parent ps)
        ^Context ctx context]
    (set! (.-ready sub) true)
    (attach (.-ready ps) (set! (.-ready ps) sub))
    (release pub)
    (leave ctx idle)))

(defn step [^Subscription sub idle]
  (let [^Process ps (.-target sub)
        ^Publisher pub (.-parent ps)
        ^Context ctx context]
    (attach (.-pending ps) (set! (.-pending ps) sub))
    (release pub)
    ((.-lcb sub))
    (leave ctx idle)))

(defn done [^Subscription sub idle]
  (let [^Process ps (.-target sub)
        ^Publisher pub (.-parent ps)
        ^Context ctx context]
    (release pub)
    ((.-rcb sub))
    (leave ctx idle)))

(defn success [^Subscription sub idle]
  (let [^Process ps (.-target sub)
        ^Publisher pub (.-parent ps)
        ^Context ctx context]
    (release pub)
    ((.-lcb sub) (.-state ps))
    (leave ctx idle)))

(defn failure [^Subscription sub idle]
  (let [^Process ps (.-target sub)
        ^Publisher pub (.-parent ps)
        ^Context ctx context]
    (release pub)
    ((.-rcb sub) (.-state ps))
    (leave ctx idle)))

(defn ready [^Process ps]
  (if ^boolean (.-owned ps)
    (set! (.-owned ps) false)
    (when (zero? (set! (.-pressure ps) (inc (.-pressure ps))))
      (let [^Context ctx context
            idle (enter ctx)]
        (schedule ps)
        (leave ctx idle)))))

(defn sub [^Publisher pub lcb rcb]
  (let [^Context ctx context
        idle (enter ctx)]
    (acquire pub)
    (let [^Process source (.-process ctx)
          ^Process target (if-some [ps (.-current pub)]
                            ps (let [ps (->Process pub 0 true nil nil nil nil nil false false false nil)]
                                 (set! (.-child ps) ps)
                                 (set! (.-state ps) ps)
                                 (set! (.-current pub) ps)
                                 (set! (.-process ctx) ps)
                                 (set! (.-input ps)
                                   ((.-effect pub)
                                    (fn
                                      ([]
                                       (ready ps))
                                      ([x]
                                       (set! (.-state ps) x)
                                       (ready ps)))
                                    (fn
                                      ([]
                                       (set! (.-flag ps) true)
                                       (ready ps))
                                      ([x]
                                       (set! (.-flag ps) true)
                                       (set! (.-state ps) x)
                                       (ready ps)))))
                                 (set! (.-process ctx) source)
                                 (publish (.-strategy pub) ps) ps))
          sub (->Subscription source target lcb rcb nil nil false nil)]
      (if ^boolean (.-failed target)
        (step sub idle)
        (subscribe (.-strategy pub) sub idle))
      sub)))

(defn unsub [^Subscription sub]
  (let [^Process ps (.-target sub)
        ^Publisher pub (.-parent ps)
        ^Context ctx context
        idle (enter ctx)]
    (acquire pub)
    (if (or (nil? (.-next sub)) (nil? (.-input ps))
          (not (identical? (.-current pub) ps)))
      (do (release pub)
          (leave ctx idle))
      (if (and (nil? (if ^boolean (.-ready sub) (.-pending ps) (.-ready ps)))
            (identical? (.-next sub) sub))
        (do (set! (.-current pub) nil)
            ((.-input ps))
            (release pub)
            (leave ctx idle))
        (unsubscribe (.-strategy pub) sub idle)))))

(defn transfer [^Subscription sub]
  (let [^Process ps (.-target sub)
        ^Publisher pub (.-parent ps)
        ^Context ctx context
        idle (enter ctx)]
    (acquire pub)
    (if (nil? (.-next sub))
      (reject (.-strategy pub) sub idle)
      (do (when ^boolean (.-dirty ps)
            (set! (.-dirty ps) false)
            (let [^Process p (.-process ctx)]
              (set! (.-process ctx) ps)
              (refresh (.-strategy pub) ps)
              (set! (.-process ctx) p)))
          (if ^boolean (.-failed ps)
            (do (set! (.-pending ps) (detach sub))
                (if (nil? (.-input ps))
                  (done sub idle)
                  (request sub idle))
                (throw (.-state ps)))
            (accept (.-strategy pub) sub idle))))))

(defn ranks [^Context ctx]
  (if-some [^Process ps (.-process ctx)]
    (let [^Publisher pub (.-parent ps)
          r (.-ranks pub)
          n (alength r)
          a (make-array (inc n))
          i (.-node pub)]
      (set! (.-node pub) (inc i))
      (dotimes [i n] (aset a i (aget r i)))
      (aset a n i) a)
    (let [a (make-array 1)
          i root]
      (set! root (inc i))
      (aset a 0 i) a)))

(defn publisher [strategy arg effect]
  (->Publisher (ranks context) strategy arg effect 0 nil))

(def memo
  (reify Strategy
    (tick [_ ^Process ps]
      (if ^boolean (.-flag ps)
        (failure-all ps)
        (success-all ps)))
    (publish [_ ^Process ps]
      (if ^boolean (.-owned ps)
        (do (set! (.-owned ps) false)
            (when (ack ps) (schedule ps)))
        (set! (.-input ps) nil)))
    (subscribe [_ ^Subscription sub idle]
      (let [^Process ps (.-target sub)]
        (if (nil? (.-input ps))
          (if ^boolean (.-flag ps)
            (failure sub idle)
            (success sub idle))
          (request sub idle))))
    (unsubscribe [_ ^Subscription sub idle]
      (let [^Process ps (.-target sub)
            ^Publisher pub (.-parent ps)]
        (set! (.-ready ps) (detach sub))
        (release pub)
        ((.-rcb sub) (Cancelled. "Memo subscription cancelled."))
        (leave context idle)))))

(def stream
  (reify Strategy
    (tick [_ ^Process ps]
      (let [^Publisher pub (.-parent ps)]
        (if ^boolean (.-owned ps)
          (do (set! (.-owned ps) false)
              (if (nil? (.-pending ps))
                (do (set! (.-state ps) ps)
                    (if (ack ps)
                      (stream-emit ps)
                      (release pub)))
                (release pub)))
          (stream-emit ps))))
    (publish [_ ^Process ps]
      (if ^boolean (.-owned ps)
        (do (set! (.-owned ps) false)
            (when (ack ps) (schedule ps)))
        (if ^boolean (.-flag ps)
          (set! (.-input ps) nil)
          (do (set! (.-dirty ps) true)
              (set! (.-owned ps) true)
              (schedule ps)))))
    (refresh [_ ^Process ps]
      (let [o (.-owned ps)]
        (try (set! (.-owned ps) false)
             (set! (.-state ps) @(.-input ps))
             (set! (.-owned ps) o)
             (catch :default e
               (crash ps e)
               (set! (.-owned ps) o)
               (when-not ^boolean o
                 (when (ack ps)
                   (schedule ps)))))))
    (subscribe [_ ^Subscription sub idle]
      (let [^Process ps (.-target sub)]
        (if (nil? (.-input ps))
          (done sub idle)
          (if ^boolean (.-owned ps)
            (step sub idle)
            (request sub idle)))))
    (unsubscribe [_ ^Subscription sub idle]
      (let [^Process ps (.-target sub)
            ^Publisher pub (.-parent ps)]
        (if ^boolean (.-ready sub)
          (do (set! (.-ready ps) (detach sub))
              (release pub)
              ((.-lcb sub)))
          (do (stream-ack sub)
              (release pub)))
        (leave context idle)))
    (accept [_ ^Subscription sub idle]
      (let [^Process ps (.-target sub)
            result (.-state ps)]
        (stream-ack sub)
        (if (nil? (.-input ps))
          (done sub idle)
          (request sub idle))
        result))
    (reject [_ ^Subscription sub idle]
      (done sub idle)
      (throw (Cancelled. "Stream subscription cancelled.")))))

(def signal
  (reify Strategy
    (tick [_ ^Process ps]
      (let [^Publisher pub (.-parent ps)]
        (if ^boolean (.-owned ps)
          (do (set! (.-owned ps) false)
              (if (ack ps)
                (signal-emit ps)
                (release pub)))
          (signal-emit ps))))
    (publish [_ ^Process ps]
      (if ^boolean (.-owned ps)
        (do (crash ps (js/Error. "Uninitialized flow."))
            (schedule ps))
        (if ^boolean (.-flag ps)
          (do (crash ps (js/Error. "Empty flow."))
              (set! (.-input ps) nil))
          (set! (.-dirty ps) true))))
    (refresh [_ ^Process ps]
      (let [^Publisher pub (.-parent ps)]
        (set! (.-owned ps) true)
        (schedule ps)
        (try (let [input (.-input ps)
                   sg (.-arg pub)
                   r (loop [r @input]
                       (if (or ^boolean (.-owned ps) ^boolean (.-flag ps))
                         r (do (set! (.-owned ps) true)
                               (recur (sg r @input)))))]
               (set! (.-state ps)
                 (if (identical? (.-state ps) ps)
                   r (sg (.-state ps) r)))
               (let [^Subscription head (.-pending ps)]
                 (loop [^Subscription sub head]
                   (set! (.-state sub)
                     (if (identical? (.-state sub) ps)
                       r (sg (.-state sub) r)))
                   (let [sub (.-next sub)]
                     (when-not (identical? sub head)
                       (recur sub))))))
             (catch :default e
               (crash ps e)))))
    (subscribe [_ ^Subscription sub idle]
      (let [^Process ps (.-target sub)]
        (set! (.-state sub) (.-state ps))
        (step sub idle)))
    (unsubscribe [_ ^Subscription sub idle]
      (let [^Process ps (.-target sub)
            ^Publisher pub (.-parent ps)]
        (if ^boolean (.-ready sub)
          (do (set! (.-ready ps) (detach sub))
              (release pub)
              ((.-lcb sub)))
          (do (set! (.-pending ps) (detach sub))
              (release pub)))
        (leave context idle)))
    (accept [_ ^Subscription sub idle]
      (let [^Process ps (.-target sub)
            result (.-state sub)]
        (set! (.-state sub) ps)
        (set! (.-pending ps) (detach sub))
        (if (nil? (.-input ps))
          (done sub idle)
          (request sub idle))
        result))
    (reject [_ ^Subscription sub idle]
      (done sub idle)
      (throw (Cancelled. "Signal subscription cancelled.")))))