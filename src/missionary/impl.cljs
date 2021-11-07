(ns ^:no-doc missionary.impl
  (:import missionary.Cancelled))

(defn nop [])

(def blk)
(def cpu)

(defn absolver [s f]
  (fn [t] (try (s (t)) (catch :default e (f e)))))

(defn thunk [e t s f]
  (throw (js/Error. "Unsupported operation.")))

(defn publisher [f]
  (throw (js/Error. "Unsupported operation.")))

(defn subscribe [pub n t]
  (throw (js/Error. "Unsupported operation.")))

;;;;;;;;;;;;;;
;; DATAFLOW ;;
;;;;;;;;;;;;;;

(defn send-rf [x !] (! x) x)

(deftype Dataflow [^:mutable bound
                   ^:mutable value
                   ^:mutable watch]
  IFn
  (-invoke [_ t]
    (when-not bound
      (set! bound true)
      (set! value t)
      (reduce send-rf t (persistent! watch))
      (set! watch nil)) value)
  (-invoke [_ s! f!]
    (if bound
      (do (s! value) nop)
      (let [! #(s! %)]
        (set! watch (conj! watch !))
        #(when-not bound
           (when (contains? watch !)
             (set! watch (disj! watch !))
             (f! (Cancelled. "Dataflow variable dereference cancelled."))))))))

(defn dataflow []
  (->Dataflow false nil (transient #{})))


;;;;;;;;;;;;;;;;
;; RENDEZVOUS ;;
;;;;;;;;;;;;;;;;

(deftype Rendezvous [^:mutable readers
                     ^:mutable writers]
  IFn
  (-invoke [_ t]
    (fn [s! f!]
      (if-some [[!] (seq readers)]
        (do (set! readers (disj readers !))
            (! t) (s! nil) nop)
        (let [! #(s! nil)]
          (set! writers (assoc writers ! t))
          #(when (contains? writers !)
             (set! writers (dissoc writers !))
             (f! (Cancelled. "Rendez-vous give cancelled.")))))))
  (-invoke [_ s! f!]
    (if-some [[[! t]] (seq writers)]
      (do (set! writers (dissoc writers !))
          (!) (s! t) nop)
      (let [! #(s! %)]
        (set! readers (conj readers !))
        #(when (contains? readers !)
           (set! readers (disj readers !))
           (f! (Cancelled. "Rendez-vous take cancelled.")))))))

(defn rendezvous []
  (->Rendezvous #{} {}))


;;;;;;;;;;;;;
;; MAILBOX ;;
;;;;;;;;;;;;;

(deftype Mailbox [^:mutable enqueue
                  ^:mutable dequeue
                  ^:mutable readers]
  IFn
  (-invoke [_ t]
    (if-some [[!] (seq readers)]
      (do (set! readers (disj readers !)) (! t))
      (do (.push enqueue t) nil)))
  (-invoke [_ s! f!]
    (if (zero? (alength dequeue))
      (if (zero? (alength enqueue))
        (let [! #(s! %)]
          (set! readers (conj readers !))
          #(when (contains? readers !)
             (set! readers (disj readers !))
             (f! (Cancelled. "Mailbox fetch cancelled."))))
        (let [tmp enqueue]
          (set! enqueue dequeue)
          (set! dequeue (.reverse tmp))
          (s! (.pop tmp)) nop))
      (do (s! (.pop dequeue)) nop))))

(defn mailbox []
  (->Mailbox (array) (array) #{}))


;;;;;;;;;;;;;;;
;; SEMAPHORE ;;
;;;;;;;;;;;;;;;

(deftype Semaphore [^:mutable available
                    ^:mutable readers]
  IFn
  (-invoke [_]
    (if-some [[!] (seq readers)]
      (do (set! readers (disj readers !)) (!))
      (do (set! available (inc available)) nil)))
  (-invoke [_ s! f!]
    (if (zero? available)
      (let [! #(s! nil)]
        (set! readers (conj readers !))
        #(when (contains? readers !)
           (set! readers (disj readers !))
           (f! (Cancelled. "Semaphore acquire cancelled."))))
      (do (set! available (dec available))
          (s! nil) nop))))

(defn semaphore [n]
  (->Semaphore n #{}))


;;;;;;;;;;;;;;
;; RACEJOIN ;;
;;;;;;;;;;;;;;

(declare racejoin-cancel)
(deftype RaceJoin
  [combinator
   joincb racecb
   cancel result
   ^number join
   ^number race]
  IFn
  (-invoke [j] (racejoin-cancel j)))

(defn racejoin-cancel [^RaceJoin j]
  (dotimes [i (alength (.-cancel j))]
    ((aget (.-cancel j) i))))

(defn racejoin-terminated [^RaceJoin j]
  (let [n (inc (.-join j))]
    (set! (.-join j) n)
    (when (== n (alength (.-result j)))
      (let [w (.-race j)]
        (if (neg? w)
          (try ((.-joincb j) (.apply (.-combinator j) nil (.-result j)))
               (catch :default e ((.-racecb j) e)))
          ((.-racecb j) (aget (.-result j) w)))))))

(defn race-join [r c ts s f]
  (let [n (count ts)
        i (iter ts)
        j (->RaceJoin c (if r f s) (if r s f) (object-array n) (object-array n) 0 -2)]
    (loop [index 0]
      (let [join (fn [x]
                   (aset (.-result j) index x)
                   (racejoin-terminated j))
            race (fn [x]
                   (let [w (.-race j)]
                     (when (neg? w)
                       (set! (.-race j) index)
                       (when (== -1 w) (racejoin-cancel j))))
                   (join x))]
        (aset (.-cancel j) index ((.next i) (if r race join) (if r join race)))
        (when (.hasNext i) (recur (inc index)))))
    (if (== -2 (.-race j))
      (set! (.-race j) -1)
      (racejoin-cancel j)) j))


;;;;;;;;;;;;
;; TIMING ;;
;;;;;;;;;;;;

(declare sleep-cancel)
(deftype Sleep
  [failure handler
   ^boolean pending]
  IFn
  (-invoke [s] (sleep-cancel s)))

(defn sleep-cancel [^Sleep s]
  (when (.-pending s)
    (set! (.-pending s) false)
    (js/clearTimeout (.-handler s))
    ((.-failure s) (Cancelled. "Sleep cancelled."))))

(defn sleep [d x s f]
  (let [slp (->Sleep f nil true)]
    (set! (.-handler slp) (js/setTimeout #(do (set! (.-pending slp) false) (s x)) d)) slp))


;;;;;;;;;;;;;;;
;; THREADING ;;
;;;;;;;;;;;;;;;

(def threading-unsupported (ex-info "Threading operations are not supported." {}))

(def via (constantly threading-unsupported))


;;;;;;;;;;;
;; NEVER ;;
;;;;;;;;;;;

(deftype Never [f ^boolean ^:mutable alive]
  IFn
  (-invoke [_]
    (when alive
      (set! alive false)
      (f (Cancelled. "Never cancelled.")))))

(defn never [f] (->Never f true))


;;;;;;;;;;;;
;; FIBERS ;;
;;;;;;;;;;;;

(defprotocol Fiber
  (poll [_])
  (task [_ t])
  (flow-concat [_ f])
  (flow-switch [_ f])
  (flow-gather [_ f])
  (unpark [_]))

(def fiber-current)

(defn fiber-poll        []  (poll fiber-current))
(defn fiber-task        [t] (task fiber-current t))
(defn fiber-flow-concat [f] (flow-concat fiber-current f))
(defn fiber-flow-switch [f] (flow-switch fiber-current f))
(defn fiber-flow-gather [f] (flow-gather fiber-current f))
(defn fiber-unpark      []  (unpark fiber-current))

(deftype Sequential [coroutine success failure resume rethrow token ^boolean busy ^boolean failed current]
  IFn
  (-invoke [_]
    (when-some [t token]
      (set! (.-token _) nil)
      (t)))
  Fiber
  (poll [_]
    (when (nil? token)
      (throw (Cancelled. "Process cancelled."))))
  (task [_ t]
    (let [c (t resume rethrow)]
      (if (nil? token)
        (c) (set! (.-token _) c)) nop))
  (flow-concat [_ f]
    (throw (js/Error. "Unsupported operation.")))
  (flow-switch [_ f]
    (throw (js/Error. "Unsupported operation.")))
  (flow-gather [_ f]
    (throw (js/Error. "Unsupported operation.")))
  (unpark [_]
    (let [e failed
          x current]
      (set! (.-failed _) false)
      (set! (.-current _) nil)
      (if e (throw x) x))))

(defn sp-step [^Sequential sp]
  (let [pf fiber-current]
    (set! fiber-current sp)
    (loop []
      (try
        (let [x ((.-coroutine sp))]
          (when-not (identical? x nop)
            ((.-success sp) x)))
        (catch :default e
          ((.-failure sp) e)))
      (when (set! (.-busy sp) (not (.-busy sp)))
        (recur)))
    (set! fiber-current pf)))

(defn sp [c s f]
  (let [sp (->Sequential c s f nil nil nop true false nil)]
    (set! (.-resume sp)
          (fn [x]
            (set! (.-current sp) x)
            (when (set! (.-busy sp) (not (.-busy sp)))
              (sp-step sp))))
    (set! (.-rethrow sp)
          (fn [e]
            (set! (.-failed sp) true)
            ((.-resume sp) e)))
    (sp-step sp) sp))



(def ^:const CONCAT 0)
(def ^:const SWITCH 1)
(def ^:const GATHER 2)

(deftype Choice [^Choice parent backtrack iterator ^number type ^boolean done token ready]
  IFn
  (-invoke [_]
    (when-some [t token]
      (set! (.-token _) nil)
      (iterator) (t))))

(declare ap-swap)
(declare ap-choice)
(deftype Gather [process coroutine ^boolean failed current ^Choice choice ^Gather next resume rethrow token ^boolean busy]
  Fiber
  (poll [_]
    (when (if-some [c choice]
            (or (nil? (.-token c))
                (and (== SWITCH (.-type c))
                     (nil? (.-ready c))
                     (not (.-done c))))
            (nil? token))
      (throw (Cancelled. "Process cancelled."))))
  (task [_ t]
    (ap-swap _ (t resume rethrow)) nop)
  (flow-concat [_ f]
    (ap-choice _ f CONCAT) nop)
  (flow-switch [_ f]
    (ap-choice _ f SWITCH) nop)
  (flow-gather [_ f]
    (ap-choice _ f GATHER) nop)
  (unpark [_]
    (let [e failed
          x current]
      (set! (.-failed _) false)
      (set! (.-current _) nil)
      (if e (throw x) x))))

(declare ap-cancel)
(declare ap-emitter)
(declare ap-terminate)
(declare ap-more)
(declare ap-ready)
(declare ap-reverse)
(deftype Ambiguous [notifier terminator ^Gather head queue alive]
  IFn
  (-invoke [_]
    (let [a alive]
      (when-not (number? a)
        (set! (.-alive _) (count a))
        (reduce (fn [_ g] (ap-cancel g)) nil a))))
  IDeref
  (-deref [_]
    (try (unpark head)
         (catch :default e
           (set! (.-notifier _) #(try @_ (catch :default _)))
           (_) (throw e))
         (finally
           (let [next (ap-emitter (.-next head))]
             (if-some [c (.-choice head)]
               (when (nil? (ap-ready c))
                 (ap-more head))
               (ap-terminate head))
             (when-some [next (if (nil? next)
                                (loop []
                                  (if-some [prev queue]
                                    (do (set! (.-queue _) nil)
                                        (or (-> prev ap-reverse ap-emitter) (recur)))
                                    (do (set! (.-queue _) nop) nil))) next)]
               (set! (.-head _) next)
               (notifier)))))))

(defn ap-swap [^Gather g t]
  (if-some [c (.-choice g)]
    (do (if (nil? (.-token c))
          (t) (set! (.-token c) t))
        (when (== SWITCH (.-type c))
          (if (nil? (.-ready c))
            (when-not (.-done c) (t))
            (set! (.-ready c) t))))
    (if (nil? (.-token g))
      (t) (set! (.-token g) t))))

(defn ap-choice [^Gather g f t]
  (let [c (->Choice (.-choice g) (.-coroutine g) nil t false nop nop)
        n #(if-some [t (ap-ready c)]
             (t) (ap-more g))
        t #(do (set! (.-done c) true)
               (when (nil? (ap-ready c))
                 (ap-more g)))]
    (set! (.-iterator c) (f n t))
    (ap-swap g c)
    (set! (.-choice g) c)
    (when (nil? (ap-ready c)) (ap-more g))))

(defn ap-emitter [^Gather g]
  (loop [g g]
    (when (some? g)
      (if-some [c (.-choice g)]
        (if (== GATHER (.-type c))
          (let [h (.-next g)]
            (when (nil? (ap-ready (.-choice g)))
              (ap-more g))
            (recur h)) g) g))))

(defn ap-terminate [^Gather g]
  (let [^Ambiguous p (.-process g)
        a (.-alive p)]
    (when (zero? (if (number? a)
                   (set! (.-alive p) (dec a))
                   (count (set! (.-alive p) (disj a g)))))
      ((.-terminator p)))))

(defn ap-run [c ^Gather g x]
  (set! (.-coroutine g) c)
  ((.-resume g) x))

(defn ap-cancel [^Gather g]
  (when-some [t (.-token g)]
    (set! (.-token g) nil) (t)))

(defn ap-gather [^Ambiguous p]
  (let [g (->Gather p nil false nil nil nil nil nil nop false)]
    (set! (.-resume g)
          (fn [x]
            (set! (.-current g) x)
            (when (set! (.-busy g) (not (.-busy g)))
              (let [pf fiber-current]
                (set! fiber-current g)
                (loop []
                  (let [x (try ((.-coroutine g))
                               (catch :default e
                                 (set! (.-failed g) true) e))]
                    (if (set! (.-busy g) (not (.-busy g)))
                      (recur)
                      (do (set! fiber-current pf)
                          (when-not (identical? x nop)
                            (set! (.-current g) x)
                            (let [^Ambiguous p (.-process g)]
                              (if (identical? nop (.-queue p))
                                (do (set! (.-queue p) nil)
                                    (set! (.-next g) nil)
                                    (set! (.-head p) g)
                                    ((.-notifier p)))
                                (do (set! (.-next g) (.-queue p))
                                    (set! (.-queue p) g)))))))))))))
    (set! (.-rethrow g)
          (fn [e]
            (set! (.-failed g) true)
            ((.-resume g) e)))
    (let [a (.-alive p)]
      (set! (.-alive p) (if (number? a) (inc a) (conj a g)))
      (when (number? a) (ap-cancel g))) g))

(defn ap-more [^Gather g]
  (let [^Ambiguous p (.-process g)]
    (while
      (and
        (let [c (.-choice g)]
          (if (.-done c)
            (if (nil? (set! (.-choice g) (.-parent c)))
              (do (ap-terminate g) false) true)
            (if (.-failed g)
              (do (try @(.-iterator c) (catch :default _)) true)
              (try (let [x @(.-iterator c)
                         r (if (== GATHER (.-type c))
                             (if (identical? nop (.-queue p))
                               true (do (set! (.-next g) (.-queue p))
                                        (set! (.-queue p) g) false))
                             false)]
                     ((.-backtrack c) ap-run (if (== GATHER (.-type c)) (ap-gather p) g) x) r)
                   (catch :default e
                     (set! (.-coroutine g) (.-backtrack c))
                     ((.-rethrow g) e) false)))))
        (nil? (ap-ready (.-choice g)))))))

(defn ap-ready [^Choice c]
  (let [r (.-ready c)]
    (set! (.-ready c) (when (nil? r) nop)) r))

(defn ap-reverse [^Gather g]
  (loop [prev g
         next nil]
    (if (nil? prev)
      next (let [swap (.-next prev)]
             (set! (.-next prev) next)
             (recur swap prev)))))

(defn ap [c n t]
  (let [p (->Ambiguous n t nil nop #{})]
    (ap-run c (ap-gather p) nil) p))


;;;;;;;;;;;;;;;
;; ENUMERATE ;;
;;;;;;;;;;;;;;;

(declare enumerate-deref)
(declare enumerate-cancel)
(deftype Enumerate
  [iterator notifier terminator]
  IFn
  (-invoke [e] (enumerate-cancel e))
  IDeref
  (-deref [e] (enumerate-deref e)))

(defn enumerate-cancel [^Enumerate e]
  (set! (.-iterator e) nil))

(defn enumerate-pull [^Enumerate e]
  (if (.hasNext (.-iterator e))
    ((.-notifier e))
    (do (set! (.-iterator e) nil)
        ((.-terminator e)))))

(defn enumerate-deref [^Enumerate e]
  (if-some [i (.-iterator e)]
    (let [x (.next i)]
      (enumerate-pull e) x)
    (do ((.-terminator e))
        (throw (Cancelled. "Seed cancelled")))))

(defn enumerate [coll n t]
  (doto (->Enumerate (iter coll) n t) (enumerate-pull)))


;;;;;;;;;;;;;;;
;; TRANSFORM ;;
;;;;;;;;;;;;;;;

(declare transform-cancel)
(declare transform-deref)
(deftype Transform
  [reducer iterator
   notifier terminator
   buffer
   ^number offset
   ^number length
   ^number error
   ^boolean busy
   ^boolean done]
  IFn
  (-invoke [t] (transform-cancel t))
  IDeref
  (-deref [t] (transform-deref t)))

(defn transform-feed
  ([^Transform t] t)
  ([^Transform t x]
   (if (== (.-length t) (alength (.-buffer t)))
     (.push (.-buffer t) x)
     (aset (.-buffer t) (.-length t) x))
   (set! (.-length t) (inc (.-length t))) t))

(defn transform-pull [^Transform t]
  (loop []
    (if (.-done t)
      (if-some [rf (.-reducer t)]
        (do (set! (.-offset t) 0)
            (set! (.-length t) 0)
            (try (rf t)
                 (catch :default e
                   (set! (.-error t) (.-length t))
                   (transform-feed t e)))
            (set! (.-reducer t) nil)
            (if (zero? (.-length t))
              (recur)
              (do ((.-notifier t))
                  (when (set! (.-busy t) (not (.-busy t))) (recur)))))
        ((.-terminator t)))
      (if-some [rf (.-reducer t)]
        (do (set! (.-offset t) 0)
            (set! (.-length t) 0)
            (try (when (reduced? (rf t @(.-iterator t)))
                   (rf t)
                   (set! (.-reducer t) nil)
                   (transform-cancel t))
                 (catch :default e
                   (set! (.-error t) (.-length t))
                   (transform-feed t e)
                   (set! (.-reducer t) nil)
                   (transform-cancel t)))
            (if (pos? (.-length t))
              ((.-notifier t))
              (when (set! (.-busy t) (not (.-busy t))) (recur))))
        (do (try @(.-iterator t) (catch :default _))
            (when (set! (.-busy t) (not (.-busy t))) (recur)))))))

(defn transform-cancel [^Transform t]
  ((.-iterator t)))

(defn transform-deref [^Transform t]
  (let [o (.-offset t)
        x (aget (.-buffer t) o)]
    (aset (.-buffer t) o nil)
    (set! (.-offset t) (inc o))
    (if (== (.-offset t) (.-length t))
      (when (set! (.-busy t) (not (.-busy t)))
        (transform-pull t)) ((.-notifier t)))
    (if (== o (.-error t)) (throw x) x)))

(defn transform [xf flow n t]
  (let [t (->Transform (xf transform-feed) nil n t (object-array 1) 0 0 -1 true false)
        n #(when (set! (.-busy t) (not (.-busy t))) (transform-pull t))]
    (set! (.-iterator t) (flow n #(do (set! (.-done t) true) (n))))
    (n) t))


;;;;;;;;;;;
;; WATCH ;;
;;;;;;;;;;;

(declare watch-cancel)
(declare watch-deref)
(deftype Watch
  [reference notifier terminator
   ^boolean sync]
  IFn
  (-invoke [w] (watch-cancel w))
  IDeref
  (-deref [w] (watch-deref w)))

(defn watch-cb [^Watch w _ _ _]
  (when (.-sync w)
    (set! (.-sync w) false)
    ((.-notifier w))))

(defn watch-cancel [^Watch w]
  (when (some? (.-notifier w))
    (set! (.-notifier w) nil)
    (remove-watch (.-reference w) w)
    (when (.-sync w)
      (set! (.-sync w) false)
      ((.-terminator w)))))

(defn watch-deref [^Watch w]
  (set! (.-sync w) true)
  (when (nil? (.-notifier w))
    ((.-terminator w)))
  @(.-reference w))

(defn watch [r n t]
  (let [w (->Watch r n t false)]
    (add-watch r w watch-cb)
    (n) w))


;;;;;;;;;;;;;
;; OBSERVE ;;
;;;;;;;;;;;;;

(declare observe-cancel)
(declare observe-deref)
(deftype Observe
  [notifier terminator
   unsub current
   ^boolean failed
   ^boolean cancelled]
  IFn
  (-invoke [o] (observe-cancel o))
  IDeref
  (-deref [o] (observe-deref o)))

(defn observe-unsub [^Observe o]
  (try ((.-unsub o))
       ((.-terminator o))
       (catch :default e
         (set! (.-failed o) true)
         (set! (.-current o) e)
         ((.-notifier o)))))

(defn observe-cancel [^Observe o]
  (when-not (.-cancelled o)
    (set! (.-cancelled o) true)
    (when (identical? nop (.-current o))
      (observe-unsub o))))

(defn observe-deref [^Observe o]
  (let [x (.-current o)]
    (set! (.-current o) nop)
    (when (.-cancelled o)
      (when (.-failed o)
        ((.-terminator o))
        (throw x))
      (observe-unsub o)) x))

(defn observe [s n t]
  (let [o (->Observe n t nil nop false false)]
    (try (set! (.-unsub o)
               (s (fn [x]
                    (when-not (.-cancelled o)
                      (when-not (identical? nop (.-current o))
                        (throw (ex-info "Unable to process event : consumer is not ready." {})))
                      (set! (.-current o) x)
                      ((.-notifier o))))))
         (catch :default e
           (set! (.-cancelled o) true)
           (set! (.-failed o) true)
           (let [x (.-current o)]
             (set! (.-current o) e)
             (when (identical? nop x)
               (n))))) o))


;;;;;;;;;;;;
;; BUFFER ;;
;;;;;;;;;;;;

(declare buffer-cancel)
(declare buffer-deref)
(deftype Buffer
  [notifier terminator
   iterator buffer
   ^number push
   ^number pull
   ^number failed
   ^boolean busy
   ^boolean done]
  IFn
  (-invoke [b] (buffer-cancel b))
  IDeref
  (-deref [b] (buffer-deref b)))

(defn buffer-cancel [^Buffer b]
  ((.-iterator b)))

(defn buffer-more [^Buffer b]
  (loop []
    (let [p (.-push b)
          q (.-pull b)
          c (alength (.-buffer b))]
      (if (.-done b)
        (if (== c (.-pull b))
          ((.-terminator b))
          (aset (.-buffer b) p nop))
        (if (neg? (.-failed b))
          (let [n (mod (inc p) c)
                r (if (== q c) (set! (.-pull b) p) q)]
            (aset (.-buffer b) p
                  (try @(.-iterator b)
                       (catch :default e
                         (buffer-cancel b)
                         (set! (.-failed b) p) e)))
            (set! (.-push b) (if (== n r) c n))
            (when (== q c) ((.-notifier b)))
            (when-not (== n r)
              (when (set! (.-busy b) (not (.-busy b))) (recur))))
          (do (try @(.-iterator b) (catch :default _))
              (when (set! (.-busy b) (not (.-busy b))) (recur))))))))

(defn buffer-deref [^Buffer b]
  (let [p (.-pull b)
        q (.-push b)
        c (alength (.-buffer b))
        n (mod (inc p) c)
        x (aget (.-buffer b) p)
        r (if (== q c) (set! (.-push b) n) q)]
    (aset (.-buffer b) p nil)
    (if (== n r)
      (do
        (set! (.-pull b) c)
        (when (identical? nop (aget (.-buffer b) q))
          ((.-terminator b))))
      (do
        (set! (.-pull b) n)
        ((.-notifier b))))
    (when (== q c)
      (when (set! (.-busy b) (not (.-busy b)))
        (buffer-more b)))
    (if (== (.-failed b) p) (throw x) x)))

(defn buffer [c f n t]
  (let [b (->Buffer n t nil (object-array c) 0 c -1 true false)
        n #(when (set! (.-busy b) (not (.-busy b))) (buffer-more b))]
    (set! (.-iterator b) (f n #(do (set! (.-done b) true) (n))))
    (n) b))


;;;;;;;;;
;; ZIP ;;
;;;;;;;;;

(declare zip-cancel)
(declare zip-deref)
(deftype Zip
  [combinator notifier flusher
   iterators results
   ^number pending]
  IFn
  (-invoke [z] (zip-cancel z))
  IDeref
  (-deref [z] (zip-deref z)))

(defn zip-cancel [^Zip z]
  (let [its (.-iterators z)]
    (dotimes [i (alength its)]
      (when-some [it (aget its i)] (it)))))

(defn zip-deref [^Zip z]
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
           (when (identical? (.-notifier z) (.-flusher z)) (zip-cancel z))))))

(defn zip [f fs n t]
  (let [c (count fs)
        i (iter fs)
        z (->Zip f n nil (object-array c) (object-array c) 0)]
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
                       (zip-cancel z)
                       (when (zero? p) ((.-notifier z))))))))
      (when (.hasNext i) (recur (inc index))))
    (when (zero? (set! (.-pending z) (+ (.-pending z) c)))
      ((.-notifier z))) z))
