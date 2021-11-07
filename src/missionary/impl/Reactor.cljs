(ns missionary.impl.Reactor)

(def ^{:doc "
# Events
A reactor context handles 7 kinds of events :
1. context boot
2. context cancellation
3. publisher notification
4. publisher termination
5. publisher cancellation
6. subscription transfer
7. subscription cancellation

Each event is processed immediately. The processing of non-reentrant events is followed by a succession of propagation
turns until no more publishers are ready to emit, then a check for termination.

# Ordering
Publisher ordering is derived from the lexicographical ordering of their ranks, where the `rank` of a publisher is the
sequence of birth ranks of its successive parents (root first), stored in an array. The birth rank is computed by
incrementing the field `children` of the parent on each publisher spawning.

# Backpressure
Stream backpressure is tracked with the publisher field `pending` holding the number of subscriptions notified but not
yet transferred, +1 if active this turn. It is incremented on emission and for each subscription notification, and
decremented on end of active turn and for each subscription transfer. It is negative for signals (no backpressure).

# Priority queues
A context schedules publishers ready to emit in two disjoint priority queues, for the current turn and the next one.
Both queues are represented as the root of a [pairing heap](https://www.cs.cmu.edu/~sleator/papers/pairing-heaps.pdf),
or `null` if the queue is empty, respectively in `today` and `tomorrow`. Each publisher stores its first child in
`child` and its next older sibling in `sibling`. When a publisher is removed from the heap, `child` is assigned to
itself in order to efficiently check scheduling state.

# Sets
Sets are needed for 3 purposes :
1. As long as a reactor is not cancelled, it maintains a set of cancellable publishers.
2. Between two successive emissions, a publisher maintains a set of notifiable subscriptions.
3. The set of emitting streams is maintained in order to deactivate them at the end of propagation turn.

1 & 2 represent the set as a doubly-linked list. The set manager keeps references to the first and last item in `head`
and `tail` (both `null` if the set is empty), and the set items keep references to their predecessor and successor in
`prev` and `next` (`null` respectively for the first and last items). When an item is removed, `prev` is assigned to
itself in order to efficiently check set membership.

3 represents the set as a singly-linked list. The head of the list is stored in a local variable and the successor is
stored in field `active`. When an item is inactive, `active` is assigned to itself.
"} current nil)

(def err-pub-orphan (js/Error. "Publication failure : not in reactor context."))
(def err-pub-cancel (js/Error. "Publication failure : reactor cancelled."))
(def err-sub-orphan (js/Error. "Subscription failure : not in publisher context."))
(def err-sub-cancel (js/Error. "Subscription failure : publisher cancelled."))
(def err-sub-cyclic (js/Error. "Subscription failure : cyclic dependency."))

(defn lt [x y]
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

(defn link [x y]
  (if (lt (.-ranks x) (.-ranks y))
    (do (set! (.-sibling y) (.-child x))
        (set! (.-child x) y) x)
    (do (set! (.-sibling x) (.-child y))
        (set! (.-child y) x) y)))

(defn enqueue [r p]
  (if (nil? r) p (link p r)))

(defn dequeue [r]
  (loop [heap nil
         prev nil
         head (let [h (.-child r)] (set! (.-child r) r) h)]
    (if (nil? head)
      (if (nil? prev) heap (if (nil? heap) prev (link heap prev)))
      (let [next (.-sibling head)]
        (set! (.-sibling head) nil)
        (if (nil? prev)
          (recur heap head next)
          (let [head (link prev head)]
            (recur (if (nil? heap) head (link heap head)) nil next)))))))

(defn schedule [p]
  (let [ctx (.-process p)]
    (if (when-some [e (.-emitter ctx)] (lt (.-ranks e) (.-ranks p)))
      (set! (.-today    ctx) (enqueue (.-today    ctx) p))
      (set! (.-tomorrow ctx) (enqueue (.-tomorrow ctx) p)))))

(defn attach [sub]
  (let [pub (.-subscribed sub)
        prv (.-tail pub)]
    (set! (.-tail pub) sub)
    (set! (.-prev sub) prv)
    (if (nil? prv) (set! (.-head pub) sub) (set! (.-next prv) sub))))

(defn detach [pub]
  (let [ctx (.-process pub)
        prv (.-prev ctx)
        nxt (.-next ctx)]
    (if (nil? prv) (set! (.-head ctx) nxt) (set! (.-next prv) nxt))
    (if (nil? nxt) (set! (.-tail ctx) prv) (set! (.-prev nxt) prv))
    (set! (.-prev pub) pub)
    (set! (.-next pub) nil)))

(defn signal [pub f]
  (let [ctx (.-process pub)
        cur (.-current ctx)]
    (set! (.-current ctx) pub)
    (f)
    (set! (.-current ctx) cur)))

(defn cancel-ctx [ctx]
  (set! (.-cancelled ctx) nil)
  (while (some? (.-head ctx))
    ((.-head ctx))))

(defn cancel-pub [pub]
  (detach pub)
  (signal pub (.-iterator pub))
  (if (identical? (.-child pub) pub)
    (do (set! (.-child pub) nil)
        (when (< (.-pending pub) 1)
          (schedule pub)))
    (try @(.-iterator pub)
         (catch :default _)))
  (when (zero? (.-pending pub))
    (set! (.-pending pub) -1)))

(defn cancel-sub [sub]
  (let [pub (.-subscribed sub)
        prv (.-prev sub)
        nxt (.-next sub)]
    (if (nil? prv) (set! (.-head pub) nxt) (set! (.-next prv) nxt))
    (if (nil? nxt) (set! (.-tail pub) prv) (set! (.-prev nxt) prv))
    (set! (.-prev sub) sub)
    (set! (.-next sub) nil)
    (signal (.-subscriber sub) (.-terminator sub))))

(defn transfer [pub]
  (let [ctx (.-process pub)
        cur (.-current ctx)]
    (set! (.-current ctx) pub)
    (let [val (try @(.-iterator pub)
                   (catch :default e
                     (when-not (identical? pub (.-prev pub))
                       (cancel-pub pub))
                     (when-some [c (.-cancelled ctx)]
                       (set! (.-result ctx) e)
                       (set! (.-completed ctx) c)
                       (cancel-ctx ctx))
                     (.-ranks pub)))]
      (set! (.-current ctx) cur)
      (set! (.-value pub) val))))

(defn ack [pub]
  (when (zero? (set! (.-pending pub) (dec (.-pending pub))))
    (set! (.-value pub) nil)
    (when (identical? (.-prev pub) pub)
      (set! (.-pending pub) -1))
    (when (nil? (.-child pub))
      (schedule pub))))

(defn emit [pub]
  (let [stale (.-ranks pub)
        ctx (.-process pub)
        prv (.-emitter ctx)
        h (.-head pub)
        p (loop [s h p 1] (if (nil? s) p (recur (.-next (set! (.-prev s) s)) (inc p))))]
    (set! (.-head pub) nil)
    (set! (.-tail pub) nil)
    (set! (.-emitter ctx) pub)
    (if (zero? (.-pending pub))
      (do (set! (.-pending pub) p)
          (if (identical? (transfer pub) stale)
            (do (set! (.-child pub) pub)
                (set! (.-pending pub) -1)
                (set! (.-active pub) nil))
            (do (set! (.-active pub) (.-active ctx))
                (set! (.-active ctx) pub))))
      (do (set! (.-value pub) stale)
          (set! (.-active pub) nil)))
    (loop [s h]
      (when-not (nil? s)
        (let [n (.-next s)]
          (set! (.-next s) nil)
          (signal (.-subscriber s) (.-notifier s))
          (recur n))))
    (set! (.-emitter ctx) prv)))

(defn done [ctx]
  (loop []
    (when-some [p (.-active ctx)]
      (set! (.-active ctx) (.-active p))
      (set! (.-active p) p)
      (ack p)
      (recur)))
  (let [p (.-tomorrow ctx)]
    (set! (.-tomorrow ctx) nil) p))

(defn enter [ctx]
  (doto current (-> (identical? ctx) (when-not (set! current ctx)))))

(defn leave [ctx prv]
  (when-not (identical? ctx prv)
    (loop []
      (when-some [p (done ctx)]
        (loop [p p]
          (set! (.-today ctx) (dequeue p))
          (emit p)
          (when-some [p (.-today ctx)]
            (recur p)))
        (recur)))
    (when (zero? (.-running ctx))
      (set! (.-cancelled ctx) nil)
      ((.-completed ctx) (.-result ctx)))
    (set! current prv) nil))

(deftype Failer [t e]
  IFn
  (-invoke [_])
  IDeref
  (-deref [_]
    (t) (throw e)))

(defn failer [n t e]
  (n) (->Failer t e))

(deftype Subscription
  [notifier terminator subscriber subscribed prev next ^boolean cancelled]
  IFn
  (-invoke [this]
    (if (identical? prev this)
      (set! (.-cancelled this) true)
      (let [ctx (.-process subscriber)
            cur (enter ctx)]
        (cancel-sub this)
        (leave ctx cur))))
  IDeref
  (-deref [this]
    (let [pub subscribed
          val (.-value pub)
          ctx (.-process pub)
          stale (.-ranks pub)
          cur (enter ctx)]
      (when (pos? (.-pending pub)) (ack pub))
      (let [val (if (and (identical? val stale)
                      (not (identical? (.-prev pub) pub)))
                  (do (set! (.-pending pub) 1)
                      (loop []
                        (let [val (transfer pub)]
                          (if (identical? (.-child pub) pub)
                            (do (set! (.-pending pub) -1) val)
                            (do (set! (.-child pub) pub) (recur))))))
                  val)]
        (if (or cancelled
              (and (identical? (.-prev pub) pub)
                (identical? (.-child pub) pub)))
          (signal subscriber terminator)
          (attach this))
        (leave ctx cur)
        (doto val
          (-> (identical? stale)
            (when (throw err-sub-cancel))))))))

(deftype Publisher
  [process ranks iterator value ^number children ^number pending prev next child sibling active head tail]
  IFn
  (-invoke [this]
    (when-not (identical? prev this)
      (let [ctx process
            cur (enter ctx)]
        (when-not (and (identical? value ranks)
                    (or (identical? (transfer this) ranks)
                      (identical? prev this)))
          (cancel-pub this))
        (leave ctx cur))))
  (-invoke [this n t]
    (let [ctx process
          cur (.-current ctx)]
      (if (and (identical? ctx current) (some? cur))
        (if (and (not (identical? this cur)) (lt ranks (.-ranks cur)))
          (let [sub (->Subscription n t cur this nil nil false)]
            (set! (.-prev sub) sub)
            (if (identical? active this)
              (if (identical? prev this) (t) (attach sub))
              (do (when (pos? pending) (set! (.-pending this) (inc pending)))
                  (n))) sub)
          (failer n t err-sub-cyclic))
        (failer n t err-sub-orphan)))))

(deftype Process
  [completed cancelled result ^number children ^number running active current emitter today tomorrow head tail]
  IFn
  (-invoke [this]
    (when-not (nil? cancelled)
      (let [cur (enter this)]
        (cancel-ctx this)
        (leave this cur)))))

(defn run [b s f]
  (let [ctx (->Process nil f nil 0 0 nil nil nil nil nil nil nil)
        cur (enter ctx)]
    (try (let [r (b)]
           (when-not (nil? (.-cancelled ctx))
             (set! (.-result ctx) r)
             (set! (.-completed ctx) s)))
         (catch :default e
           (when-some [c (.-cancelled ctx)]
             (set! (.-result ctx) e)
             (set! (.-completed ctx) c)
             (cancel-ctx ctx))))
    (leave ctx cur) ctx))

(defn publish [f d]
  (let [ctx (doto current
              (-> (nil?) (when (throw err-pub-orphan)))
              (-> (.-cancelled) (nil?) (when (throw err-pub-cancel))))
        prv (.-tail ctx)
        par (.-current ctx)
        pub (->Publisher
              ctx (if (nil? par)
                    (doto (make-array 1) (aset 0 (doto (.-children ctx) (->> (inc) (set! (.-children ctx))))))
                    (let [n (alength (.-ranks par))
                          a (make-array (inc n))]
                      (dotimes [i n] (aset a i (aget (.-ranks par) i)))
                      (doto a (aset n (doto (.-children par) (->> (inc) (set! (.-children par))))))))
              nil nil 0 1 prv nil nil nil nil nil nil)]
    (set! (.-active pub) pub)
    (set! (.-child pub) pub)
    (set! (.-tail ctx) pub)
    (if (nil? prv) (set! (.-head ctx) pub) (set! (.-next prv) pub))
    (set! (.-running ctx) (inc (.-running ctx)))
    (set! (.-current ctx) pub)
    (set! (.-iterator pub)
      (f #(let [ctx (.-process pub)
                cur (enter ctx)]
            (if (identical? (.-prev pub) pub)
              (try @(.-iterator pub)
                   (catch :default _))
              (do (set! (.-child pub) nil)
                  (when (< (.-pending pub) 1)
                    (schedule pub))))
            (leave ctx cur))
        #(let [ctx (.-process pub)
               cur (enter ctx)]
           (when-not (identical? (.-prev pub) pub)
             (detach pub)
             (while (some? (.-head pub))
               (cancel-sub (.-head pub))))
           (leave ctx cur))))
    (set! (.-pending pub) (if d 0 -1))
    (set! (.-current ctx) par)
    (if (nil? (.-child pub))
      (do (set! (.-child pub) pub)
          (emit pub))
      (when-not d
        (cancel-pub pub)
        (throw (js/Error. "Undefined continuous flow."))))
    pub))
