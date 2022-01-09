(ns missionary.impl.Reactor
  (:import missionary.Cancelled))

(declare unsubscribe push free subscribe event)

(deftype Failer [t e]
  IFn
  (-invoke [_])
  IDeref
  (-deref [_] (t) (throw e)))

(deftype Subscription [notifier terminator subscriber subscribed prev next]
  IFn
  (-invoke [this] (unsubscribe this) nil)
  IDeref
  (-deref [this] (push this)))

(deftype Publisher [process iterator ranks ^number pending ^number children ^boolean live ^boolean busy ^boolean done
                    value prev next child sibling active subs]
  IFn
  (-invoke [this] (free this) nil)
  (-invoke [this n t] (subscribe this n t)))

(deftype Process [success failure error kill boot alive active current reaction schedule subscriber delayed]
  IFn
  (-invoke [_] (event kill) nil))

(def stale (js-obj))
(def error (js-obj))

(def ^Process current nil)
(def ^Process delayed nil)

(defn ^boolean lt [x y]
  (if (nil? x)
    true
    (if (nil? y)
      false
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
            (> xl yl)))))))

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

(defn schedule [^Publisher pub]
  (let [ps (.-process pub)]
    (if-some [sch (.-schedule ps)]
      (set! (.-schedule ps) (link pub sch))
      (do (set! (.-schedule ps) pub)
          (set! (.-delayed ps) delayed)
          (set! delayed ps)))))

(defn pull [^Publisher pub]
  (let [ps (.-process pub)
        cur (.-subscriber ps)]
    (set! (.-subscriber ps) pub)
    (try
      (set! (.-value pub) @(.-iterator pub))
      (catch :default e
        (set! (.-value pub) error)
        (when (identical? ps (.-error ps))
          (set! (.-error ps) e)
          (let [k (.-kill ps)]
            (when (set! (.-busy k) (not (.-busy k)))
              (schedule k))))))
    (set! (.-subscriber ps) cur)))

(defn sample [^Publisher pub]
  (loop []
    (pull pub)
    (when (set! (.-busy pub) (not (.-busy pub)))
      (if (.-done pub)
        (schedule pub)
        (recur)))))

(defn touch [^Publisher pub]
  (let [ps (.-process pub)]
    (if (.-done pub)
      (let [prv (.-prev pub)]
        (set! (.-prev pub) nil)
        (if (identical? pub prv)
          (set! (.-alive ps) nil)
          (let [nxt (.-next pub)]
            (set! (.-prev nxt) prv)
            (set! (.-next prv) nxt)
            (when (identical? pub (.-alive ps))
              (set! (.-alive ps) prv)))))
      (if (identical? pub (.-active pub))
        (do (set! (.-active pub) (.-active ps))
            (set! (.-active ps) pub)
            (set! (.-pending pub) 1)
            (pull pub))
        (if (.-live pub)
          (set! (.-value pub) stale)
          (sample pub))))))

(defn ack [^Publisher pub]
  (when (zero? (set! (.-pending pub) (dec (.-pending pub))))
    (set! (.-value pub) nil)
    (when (set! (.-busy pub) (not (.-busy pub)))
      (schedule pub))))

(defn propagate [^Publisher pub]
  (let [ps (.-process pub)]
    (set! current ps)
    (loop [pub pub]
      (set! (.-reaction ps) (dequeue pub))
      (set! (.-current ps) pub)
      (touch pub)
      (when-some [t (.-subs pub)]
        (set! (.-subs pub) nil)
        (loop [s t]
          (let [s (.-next s)]
            (set! (.-prev s) nil)
            (when-not (identical? s t)
              (recur s))))
        (loop [n (.-next t)]
          (let [s n
                n (.-next s)]
            (set! (.-next s) nil)
            (when (pos? (.-pending pub))
              (set! (.-pending pub) (inc (.-pending pub))))
            (set! (.-subscriber ps) (.-subscriber s))
            ((if (nil? (.-prev pub))
               (.-terminator s)
               (.-notifier s)))
            (set! (.-subscriber ps) nil)
            (when-not (identical? s t)
              (recur n)))))
      (when-some [r (.-reaction ps)]
        (recur r)))
    (set! (.-current ps) nil)
    (set! current nil)
    (loop []
      (when-some [pub (.-active ps)]
        (set! (.-active ps) (.-active pub))
        (set! (.-active pub) (when-not (identical? error (.-value pub)) pub))
        (ack pub)
        (recur)))
    (when (nil? (.-alive ps))
      (when-some [pub (.-boot ps)]
        (set! (.-boot ps) nil)
        (let [e (.-error ps)]
          (if (identical? e ps)
            ((.-success ps) (.-value pub))
            ((.-failure ps) e)))))))

(defn hook [^Subscription s]
  (let [pub (.-subscribed s)]
    (if (nil? (.-prev pub))
      ((.-terminator s))
      (let [p (.-subs pub)]
        (set! (.-subs pub) s)
        (if (nil? p)
          (->> s
            (set! (.-prev s))
            (set! (.-next s)))
          (let [n (.-next p)]
            (set! (.-next p) s)
            (set! (.-prev n) s)
            (set! (.-prev s) p)
            (set! (.-next s) n)))))))

(defn cancel [^Publisher pub]
  (when (.-live pub)
    (set! (.-live pub) false)
    (let [ps (.-process pub)
          cur (.-subscriber ps)]
      (set! (.-subscriber ps) pub)
      ((.-iterator pub))
      (set! (.-subscriber ps) cur)
      (when (identical? stale (.-value pub))
        (sample pub)))))

(defn failer [n t e]
  (n) (->Failer t e))

(defn free [^Publisher pub]
  (when-not (identical? (.-process pub) current)
    (throw (js/Error. "Cancellation failure : not in reactor context.")))
  (cancel pub))

(defn subscribe [^Publisher pub n t]
  (let [ps (.-process pub)
        sub (.-subscriber ps)]
    (if-not (identical? ps current)
      (failer n t (js/Error. "Subscription failure : not in reactor context."))
      (if (identical? sub (.-boot ps))
        (failer n t (js/Error. "Subscription failure : not a subscriber."))
        (if (identical? sub pub)
          (failer n t (js/Error. "Subscription failure : self subscription."))
          (if (lt (.-ranks sub) (.-ranks pub))
            (failer n t (js/Error. "Subscription failure : forward subscription."))
            (let [s (->Subscription n t sub pub nil nil)]
              (if (identical? (.-active pub) pub)
                (hook s)
                (do (when (pos? (.-pending pub))
                      (set! (.-pending pub) (inc (.-pending pub))))
                    (n))) s)))))))

(defn unsubscribe [^Subscription s]
  (let [sub (.-subscriber s)
        ps (.-process sub)]
    (when-not (identical? ps current)
      (throw (js/Error. "Unsubscription failure : not in reactor context.")))
    (when-some [pub (.-subscribed s)]
      (set! (.-subscribed s) nil)
      (if-some [p (.-prev s)]
        (let [n (.-next s)]
          (set! (.-prev s) nil)
          (set! (.-next s) nil)
          (if (identical? p s)
            (set! (.-subs pub) nil)
            (do (set! (.-prev n) p)
                (set! (.-next p) n)
                (when (identical? s (.-subs pub))
                  (set! (.-subs pub) p))))
          (let [cur (.-subscriber ps)]
            (set! (.-subscriber ps) sub)
            ((.-notifier s))
            (set! (.-subscriber ps) cur)))
        (when (pos? (.-pending pub)) (ack pub))))))

(defn push [^Subscription s]
  (let [sub (.-subscriber s)
        ps (.-process sub)]
    (when-not (identical? ps current)
      (throw (js/Error. "Transfer failure : not in reactor context.")))
    (let [value (if-some [pub (.-subscribed s)]
                  (let [value (.-value pub)]
                    (if (pos? (.-pending pub))
                      (do (ack pub) value)
                      (if (identical? value stale)
                        (do (sample pub) (.-value pub))
                        value))) error)
          cur (.-subscriber ps)]
      (set! (.-subscriber ps) sub)
      (if (identical? value error)
        (do ((.-terminator s))
            (set! (.-subscriber ps) cur)
            (throw (Cancelled. "Subscription cancelled.")))
        (do (hook s)
            (set! (.-subscriber ps) cur)
            value)))))

(defn event [^Publisher pub]
  (if-some [ps current]
    (when (set! (.-busy pub) (not (.-busy pub)))
      (if (identical? ps (.-process pub))
        (if (lt (.-ranks (.-current ps)) (.-ranks pub))
          (set! (.-reaction ps)
            (if-some [r (.-reaction ps)]
              (link pub r) pub))
          (schedule pub))
        (schedule pub)))
    (do (when (set! (.-busy pub) (not (.-busy pub)))
          (propagate pub))
        (loop []
          (when-some [ps delayed]
            (set! delayed (.-delayed ps))
            (set! (.-delayed ps) nil)
            (let [pub (.-schedule ps)]
              (set! (.-schedule ps) nil)
              (propagate pub)
              (recur)))))))

(def kill
  (reify
    IDeref
    (-deref [_]
      (let [ps current]
        (when-some [t (.-alive ps)]
          (loop [pub (.-next t)]
            (cancel pub)
            (when-some [t (.-alive ps)]
              (let [pub (loop [pub pub]
                          (let [pub (.-next pub)]
                            (if (nil? (.-prev pub))
                              (recur pub) pub)))]
                (when-not (identical? pub (.-next t))
                  (recur pub)))))) true))))

(def boot
  (reify IDeref
    (-deref [_]
      ((.-value (.-boot current))))))

(def zero (object-array 0))

(defn run [init s f]
  (let [ps (->Process s f nil nil nil nil nil nil nil nil nil nil)
        k (->Publisher ps kill nil 0 0 false false false false nil nil nil nil nil nil)
        b (->Publisher ps boot zero 0 0 false false false init nil nil nil nil nil nil)]
    (set! (.-kill ps) k)
    (set! (.-boot ps) b)
    (set! (.-error ps) ps)
    (event b) ps))

(defn publish [flow continuous]
  (let [ps (doto current (-> nil? (when (throw (js/Error. "Publication failure : not in reactor context.")))))
        cur (.-subscriber ps)
        pub (->Publisher
              ps nil
              (let [n (alength (.-ranks cur))
                    a (make-array (inc n))]
                (dotimes [i n] (aset a i (aget (.-ranks cur) i)))
                (doto a (aset n (doto (.-children cur) (->> (inc) (set! (.-children cur)))))))
              0 0 true true false nil nil nil nil nil nil nil)]
    (when-not continuous (set! (.-active pub) pub))
    (if-some [p (.-alive ps)]
      (let [n (.-next p)]
        (set! (.-next p) pub)
        (set! (.-prev n) pub)
        (set! (.-prev pub) p)
        (set! (.-next pub) n))
      (->> pub
        (set! (.-prev pub))
        (set! (.-next pub))))
    (set! (.-alive ps) pub)
    (set! (.-subscriber ps) pub)
    (set! (.-iterator pub)
      (flow #(event pub)
        #(do (set! (.-done pub) true)
             (event pub))))
    (set! (.-subscriber ps) cur)
    (when (.-value (.-kill ps)) (cancel pub))
    (if (set! (.-busy pub) (not (.-busy pub)))
      (touch pub)
      (when continuous
        (cancel pub)
        (throw (js/Error. "Publication failure : undefined continuous flow."))))
    pub))