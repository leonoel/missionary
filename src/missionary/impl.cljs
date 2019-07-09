(ns missionary.impl)

(defn nop [])

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
             (f! (ex-info "Dataflow variable dereference cancelled."
                          {:cancelled :missionary/dfv-deref}))))))))

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
             (f! (ex-info "Rendez-vous give cancelled."
                          {:cancelled :missionary/rdv-give})))))))
  (-invoke [_ s! f!]
    (if-some [[[! t]] (seq writers)]
      (do (set! writers (dissoc writers !))
          (!) (s! t) nop)
      (let [! #(s! %)]
        (set! readers (conj readers !))
        #(when (contains? readers !)
           (set! readers (disj readers !))
           (f! (ex-info "Rendez-vous take cancelled."
                        {:cancelled :missionary/rdv-take})))))))

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
             (f! (ex-info "Mailbox fetch cancelled." {:cancelled :missionary/mbx-fetch}))))
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
           (f! (ex-info "Semaphore acquire cancelled." {:cancelled :missionary/sem-acquire}))))
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
    ((.-failure s) (ex-info "Sleep cancelled." {:cancelled :missionary/sleep}))))

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
      (f (ex-info "Never cancelled." {:cancelled :missionary/never})))))

(defn never [f] (->Never f true))


;;;;;;;;;;;;
;; FIBERS ;;
;;;;;;;;;;;;

(declare choice-cancel)
(deftype Choice
  [^Choice parent
   backtrack
   iterator
   ^boolean preemptive
   ^boolean done
   token
   ^boolean busy]
  IFn
  (-invoke [c] (choice-cancel c)))

(declare fiber-cancel)
(declare fiber-deref)
(deftype Fiber
  [^boolean ambiguous
   coroutine
   cb1 cb2
   ^Choice choice
   ^boolean failed
   result
   token
   ^boolean busy
   success failure]
  IFn
  (-invoke [f] (fiber-cancel f))
  IDeref
  (-deref [f] (fiber-deref f)))

(def fiber-current nil)

(defn fiber-run [^Fiber f]
  (let [p fiber-current]
    (set! fiber-current f)
    (loop []
      (try
        (let [x ((.-coroutine f))]
          (when-not (identical? x nop)
            (if (.-ambiguous f)
              (do (set! (.-result f) x)
                  ((.-cb1 f)))
              ((.-cb1 f) x))))
        (catch :default e
          (if (.-ambiguous f)
            (do (set! (.-failed f) true)
                (set! (.-result f) e)
                ((.-cb1 f)))
            ((.-cb2 f) e))))
      (when (set! (.-busy f) (not (.-busy f)))
        (recur)))
    (set! fiber-current p)))

(defn fiber-fork [c ^Fiber f x]
  (set! (.-coroutine f) c)
  ((.-success f) x))

(defn fiber-pull [^Fiber f]
  (loop []
    (let [c (.-choice f)]
      (if (.-done c)
        (if-some [c (set! (.-choice f) (.-parent c))]
          (when (set! (.-busy c) (not (.-busy c)))
            (recur)) ((.-cb2 f)))
        (if (.-failed f)
          (do (try @(.-iterator c) (catch :default _))
              (when (set! (.-busy c) (not (.-busy c))) (recur)))
          (try ((.-backtrack c) fiber-fork f @(.-iterator c))
               (catch :default e
                 (set! (.-coroutine f) (.-backtrack c))
                 (set! (.-failed f) true)
                 (set! (.-result f) e)
                 (when (set! (.-busy f) (not (.-busy f))) (fiber-run f)))))))))

(defn fiber [ambiguous coroutine cb1 cb2]
  (let [f (->Fiber ambiguous coroutine cb1 cb2 nil false nil nop true nil nil)]
    (set! (.-success f)
          (fn [x]
            (set! (.-result f) x)
            (when (set! (.-busy f) (not (.-busy f)))
              (fiber-run f))))
    (set! (.-failure f)
          (fn [e]
            (set! (.-failed f) true)
            ((.-success f) e)))
    (fiber-run f) f))

(defn fiber-cancel [^Fiber f]
  (when-some [t (.-token f)]
    (set! (.-token f) nil)
    (t)))

(defn fiber-deref [^Fiber f]
  (let [e (.-failed f)
        x (.-result f)]
    (set! (.-result f) nil)
    (if-some [^Choice c (.-choice f)]
      (when (set! (.-busy c) (not (.-busy c)))
        (fiber-pull f)) ((.-cb2 f)))
    (if e (throw x) x)))

(defn fiber-swap [^Fiber f cancel]
  (if-some [c (.-choice f)]
    (if (or (nil? (.-token c))
            (and (.-preemptive c)
                 (not (.-busy c))
                 (not (.-done c))))
      (cancel) (set! (.-token c) cancel))
    (if (nil? (.-token f))
      (cancel) (set! (.-token f) cancel))))

(defn choice-cancel [^Choice c]
  (when-some [t (.-token c)]
    (set! (.-token c) nil)
    ((.-iterator c)) (t)))

(defn fiber-poll []
  (when-some [^Fiber f fiber-current]
    (when (if-some [c (.-choice f)]
            (or (nil? (.-token c))
                (and (.-preemptive c)
                     (not (.-busy c))))
            (nil? (.-token f)))
      (throw (ex-info "Process cancelled." {:cancelled (if (.-ambiguous f) :missionary/ap :missionary/sp)})))))

(defn fiber-task [task]
  (let [^Fiber f fiber-current]
    (assert f "Can't run task : not in process.")
    (fiber-swap f (task (.-success f) (.-failure f)))
    nop))

(defn fiber-flow [flow preemptive]
  (let [^Fiber f fiber-current]
    (when (nil? f) (throw (js/Error. "Can't run flow : not in process.")))
    (when-not (.-ambiguous f) (throw (js/Error. "Can't run flow : process is not ambiguous.")))
    (let [c (->Choice (.-choice f) (.-coroutine f) nil preemptive false nop true)
          n #(if (set! (.-busy c) (not (.-busy c)))
               (fiber-pull f) ((.-token c)))
          t #(do (set! (.-done c) true)
                 (when (set! (.-busy c) (not (.-busy c)))
                   (fiber-pull f)))]
      (set! (.-iterator c) (flow n t))
      (fiber-swap f c)
      (set! (.-choice f) c)
      (n) nop)))

(defn fiber-unpark []
  (let [^Fiber f fiber-current]
    (when (nil? f) (throw (js/Error. "Can't unpark : not in process.")))
    (let [e (.-failed f)
          x (.-result f)]
      (set! (.-failed f) false)
      (set! (.-result f) nil)
      (if e (throw x) x))))

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
        (throw (ex-info "Enumeration cancelled"
                        {:cancelled :missionary/enumerate})))))

(defn enumerate [coll n t]
  (doto (->Enumerate (iter coll) n t) (enumerate-pull)))


;;;;;;;;;;;;;;;
;; AGGREGATE ;;
;;;;;;;;;;;;;;;

(declare aggregate-cancel)
(deftype Aggregate
  [reducer result
   status failure
   iterator
   ^boolean busy
   ^boolean done]
  IFn
  (-invoke [a] (aggregate-cancel a)))

(defn aggregate-cancel [^Aggregate a]
  ((.-iterator a)))

(defn aggregate-pull [^Aggregate a]
  (loop []
    (if (.-done a)
      ((.-status a) (.-result a))
      (do
        (if-some [rf (.-reducer a)]
          (try (when (reduced? (set! (.-result a) (rf (.-result a) @(.-iterator a))))
                 (a) (set! (.-reducer a) nil)
                 (set! (.-result a) @(.-result a)))
               (catch :default e
                 (a) (set! (.-reducer a) nil)
                 (set! (.-status a) (.-failure a))
                 (set! (.-result a) e)))
          (try @(.-iterator a) (catch :default _)))
        (when (set! (.-busy a) (not (.-busy a))) (recur))))))

(defn aggregate [rf init flow success failure]
  (let [a (->Aggregate rf init success failure nil true false)
        n #(when (set! (.-busy a) (not (.-busy a))) (aggregate-pull a))
        t #(do (set! (.-done a) true) (n))]
    (set! (.-iterator a) (flow n t))
    (n) a))

;;;;;;;;;;;;;;;
;; INTEGRATE ;;
;;;;;;;;;;;;;;;

(declare integrate-cancel)
(declare integrate-deref)
(deftype Integrate
  [reducer result notifier terminator iterator
   ^boolean busy
   ^boolean done
   ^boolean failed]
  IFn
  (-invoke [i] (integrate-cancel i))
  IDeref
  (-deref [i] (integrate-deref i)))

(defn integrate-cancel [^Integrate i]
  ((.-iterator i)))

(defn integrate-more [^Integrate i]
  (loop []
    (if (.-done i)
      ((.-terminator i))
      (if-some [rf (.-reducer i)]
        (do (try (when (reduced? (set! (.-result i) (rf (.-result i) @(.-iterator i))))
                   (set! (.-reducer i) nil)
                   (set! (.-result i) @(.-result i))
                   (integrate-cancel i))
                 (catch :default e
                   (set! (.-reducer i) nil)
                   (set! (.-failed i) true)
                   (set! (.-result i) e)
                   (integrate-cancel i)))
            ((.-notifier i)))
        (do (try @(.-iterator i) (catch :default _))
            (when (set! (.-busy i) (not (.-busy i))) (recur)))))))

(defn integrate-deref [^Integrate i]
  (let [x (.-result i)
        f (.-failed i)]
    (when (set! (.-busy i) (not (.-busy i))) (integrate-more i))
    (if f (throw x) x)))

(defn integrate [rf i f n t]
  (let [i (->Integrate rf i n t nil true false false)]
    (set! (.-iterator i)
          (f #(when (set! (.-busy i) (not (.-busy i))) (integrate-more i))
             #(do (set! (.-done i) true)
                  (when (set! (.-busy i) (not (.-busy i))) (integrate-more i)))))
    (n) i))

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
        (do (try (rf t)
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

(defn watch-cb [^Watch w _ _ c]
  (when (.-sync w)
    (set! (.-sync w) false)
    ((.-notifier w))))

(defn watch-cancel [^Watch w]
  (when-some [n (.-notifier w)]
    (set! (.-notifier w) nil)
    (remove-watch (.-reference w) w)
    (when (.-sync w)
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
  [notifier terminator unsub current]
  IFn
  (-invoke [o] (observe-cancel o))
  IDeref
  (-deref [o] (observe-deref o)))

(defn observe-cancel [^Observe o]
  (when (some? (.-notifier o))
    (set! (.-notifier o) nil)
    ((.-unsub o))
    (when (identical? nop (.-current o))
      ((.-terminator o)))))

(defn observe-deref [^Observe o]
  (let [x (.-current o)]
    (set! (.-current o) nop)
    (when (nil? (.-notifier o))
      ((.-terminator o))) x))

(defn observe [s n t]
  (let [o (->Observe n t nil nop)]
    (set! (.-unsub o)
          (s (fn [x]
               (when-not (identical? nop (.-current o))
                 (throw (ex-info "Unable to process event : consumer is not ready." {})))
               (set! (.-current o) x)
               ((.-notifier o))))) o))


;;;;;;;;;;;;;
;; RELIEVE ;;
;;;;;;;;;;;;;

(declare relieve-cancel)
(declare relieve-deref)
(deftype Relieve
  [reducer notifier terminator
   iterator current
   ^boolean busy
   ^boolean done
   ^boolean failed]
  IFn
  (-invoke [r] (relieve-cancel r))
  IDeref
  (-deref [r] (relieve-deref r)))

(defn relieve-cancel [^Relieve r]
  ((.-iterator r)))

(defn relieve-pull [^Relieve r]
  (loop []
    (if (.-done r)
      (when (identical? nop (.-current r)) ((.-terminator r)))
      (let [c (.-current r)]
        (try (let [x @(.-iterator r)]
               (set! (.-current r) (if (identical? nop c) x ((.-reducer r) c x))))
             (catch :default e
               (set! (.-failed r) true)
               (set! (.-current r) e)))
        (when (identical? nop c) ((.-notifier r)))))
    (when (set! (.-busy r) (not (.-busy r))) (recur))))

(defn relieve-deref [^Relieve r]
  (let [x (.-current r)]
    (set! (.-current r) nop)
    (when (.-done r) ((.-terminator r)))
    (if (.-failed r) (throw x) x)))

(defn relieve [rf f n t]
  (let [r (->Relieve rf n t nil nop true false false)
        n #(when (set! (.-busy r) (not (.-busy r))) (relieve-pull r))]
    (set! (.-iterator r) (f n #(do (set! (.-done r) true) (n))))
    (n) r))


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
          c (alength (.-buffer b))]
      (if (.-done b)
        (if (== c (.-pull b))
          ((.-terminator b))
          (aset (.-buffer b) p nop))
        (if (neg? (.-failed b))
          (let [n (mod (inc p) c)]
            (aset (.-buffer b) p
                  (try @(.-iterator b)
                       (catch :default e
                         (buffer-cancel b)
                         (set! (.-failed b) p) e)))
            (if (== n (.-pull b))
              (set! (.-push b) c)
              (do (set! (.-push b) n)
                  (when (== (.-pull b) c) ((.-notifier b)))
                  (when (set! (.-busy b) (not (.-busy b))) (recur)))))
          (do (try @(.-iterator b) (catch :default _))
              (when (set! (.-busy b) (not (.-busy b))) (recur))))))))

(defn buffer-deref [^Buffer b]
  (let [p (.-pull b)
        c (alength (.-buffer b))
        n (mod (inc p) c)
        x (aget (.-buffer b) p)]
    (aset (.-buffer b) p nil)
    (set! (.-pull b) n)
    (if (== n (.-push b))
      (if (identical? nop (aget (.-buffer b) (.-push b)))
        ((.-terminator b))
        (set! (.-pull b) c))
      (if (== (.-push b) c)
        (do (set! (.-push b) p)
            (when (set! (.-busy b) (not (.-busy b)))
              (buffer-more b)))
        ((.-notifier b))))
    (if (== (.-failed b) p) (throw x) x)))

(defn buffer [c f n t]
  (let [b (->Buffer n t nil (object-array c) 0 -1 c true false)
        n #(when (set! (.-busy b) (not (.-busy b))) (buffer-more b))]
    (set! (.-iterator b) (f n #(do (.-done b) (n))))
    (n) b))


;;;;;;;;;;;;
;; LATEST ;;
;;;;;;;;;;;;

(declare latest-cancel)
(declare latest-deref)
(deftype Latest
  [combinator notifier terminator
   iterators buffer prevs
   ^number state
   ^number alive]
  IFn
  (-invoke [l] (latest-cancel l))
  IDeref
  (-deref [l] (latest-deref l)))

(defn latest-cancel [^Latest l]
  (let [its (.-iterators l)]
    (dotimes [i (alength its)]
      ((aget its i)))))

(defn latest-deref [^Latest l]
  (let [c (alength (.-iterators l))]
    (try (loop [i (let [i (.-state l)] (set! (.-state l) -1) i)]
           (let [p (aget (.-prevs l) i)]
             (aset (.-buffer l) i @(aget (.-iterators l) i))
             (when-not (== p c) (recur p))))
         (.apply (.-combinator l) nil (.-buffer l))
         (catch :default e
           (set! (.-notifier l) #(try (latest-deref l) (catch :default _)))
           (latest-cancel l) (throw e))
         (finally
           (if (== -1 (.-state l))
             (set! (.-state l) c)
             (if (== c (.-state l))
               ((.-terminator l))
               ((.-notifier l))))))))

(defn latest [f fs n t]
  (let [c (count fs)
        i (iter fs)
        l (->Latest f n t (object-array c) (object-array c) (object-array c) c c)
        t #(let [d (dec (.-alive l))]
             (set! (.-alive l) d)
             (when (zero? d)
               (if (== -1 (.-state l))
                 (set! (.-state l) c)
                 ((.-terminator l)))))]
    (loop [index 0]
      (when (.hasNext i)
        (aset (.-prevs l) index -1)
        (aset (.-iterators l) index
              ((.next i)
                #(if (== -1 (aget (.-prevs l) index))
                   (let [s (dec (.-state l))]
                     (aset (.-prevs l) index (inc index))
                     (set! (.-state l) s)
                     (when (zero? s) ((.-notifier l))))
                   (let [s (.-state l)]
                     (aset (.-prevs l) index s)
                     (set! (.-state l) index)
                     (when (== c s) ((.-notifier l))))) t))
        (recur (inc index)))) l))


;;;;;;;;;;;;
;; GATHER ;;
;;;;;;;;;;;;
(declare gather-cancel)
(declare gather-deref)
(deftype Gather
  [notifier terminator
   iterators ready
   ^number push
   ^number pull
   ^number alive]
  IFn
  (-invoke [g] (gather-cancel g))
  IDeref
  (-deref [g] (gather-deref g)))

(defn gather-cancel [^Gather g]
  (let [its (.-iterators g)]
    (dotimes [i (alength its)]
      ((aget its i)))))

(defn gather-deref [^Gather g]
  (let [c (alength (.-ready g))
        p (.-pull g)
        n (mod (inc p) c)]
    (try @(aget (.-iterators g) (aget (.-ready g) p))
         (catch :default e
           (set! (.-notifier g) #(try (gather-deref g) (catch :default _)))
           (gather-cancel g)
           (throw e))
         (finally
           (set! (.-pull g) (if (== n (.-push g)) c n))
           (when (== c (.-push g))
             (set! (.-push g) p)
             ((.-notifier g)))))))

(defn gather [fs n t]
  (let [c (count fs)
        i (iter fs)
        g (->Gather n t (object-array c) (object-array c) 0 c c)
        t #(let [d (dec (.-alive g))]
             (set! (.-alive g) d)
             (when (zero? d)
               ((.-terminator g))))]
    (loop [index 0]
      (when (.hasNext i)
        (aset (.-iterators g) index
              ((.next i)
                #(let [p (.-push g)
                       n (mod (inc p) c)]
                   (aset (.-ready g) p index)
                   (set! (.-push g) (if (== n (.-pull g)) c n))
                   (when (== c (.-pull g))
                     (set! (.-pull g) p)
                     ((.-notifier g)))) t))
        (recur (inc index)))) g))


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
    (try (dotimes [i (alength its)]
           (aset res i @(aget its i))
           (set! (.-pending z) (inc (.-pending z))))
         (.apply (.-combinator z) nil res)
         (catch :default e
           (set! (.-notifier z) (.-flusher z))
           (throw e))
         (finally
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
      (aset (.-iterators z) i
            ((.next fs)
              #(when (zero? (set! (.-pending z) (dec (.-pending z))))
                 ((.-notifier z)))
              #(do (aset (.-iterators z) index nil)
                   (set! (.-notifier z) (.-flusher z))
                   (let [p (set! (.-pending z) (dec (.-pending z)))]
                     (when-not (neg? p)
                       (zip-cancel z)
                       (when (zero? p) ((.-notifier z))))))))
      (when (.hasNext fs) (recur (inc index))))
    (when (zero? (set! (.-pending z) (+ (.-pending z) c)))
      ((.-notifier z))) z))


;;;;;;;;;;;;
;; SAMPLE ;;
;;;;;;;;;;;;
(declare sample-cancel)
(declare sample-deref)
(deftype Sample
  [combinator notifier terminator
   sampled-it sampler-it latest
   ^boolean sampled-busy
   ^boolean sampler-busy
   ^boolean sampled-done
   ^boolean sampler-done]
  IFn
  (-invoke [s] (sample-cancel s))
  IDeref
  (-deref [s] (sample-deref s)))

(defn sample-cancel [^Sample s]
  ((.-sampled-it s))
  ((.-sampler-it s)))

(defn sample-flush [^Sample s]
  (loop []
    (try @(.-sampled-it s) (catch :default _))
    (when (set! (.-sampled-busy s) (not (.-sampled-busy s)))
      (recur))))

(defn sample-deref [^Sample s]
  (let [dirty (.-sampled-busy s)]
    (try ((.-combinator s) (if dirty
                             (set! (.-latest s) @(.-sampled-it s))
                             (.-latest s)) @(.-sampler-it s))
         (catch :default e
           (set! (.-notifier s) #(try (sample-deref s) (catch :default _)))
           (sample-cancel s)
           (throw e))
         (finally
           (when dirty (set! (.-sampled-busy s) (not (.-sampled-busy s))))
           (if (set! (.-sampler-busy s) (not (.-sampler-busy s)))
             ((.-notifier s))
             (when (.-sampler-done s)
               (if (.-sampled-done s)
                 ((.-terminator s))
                 (when (.-sampled-busy s)
                   (sample-flush s)))))))))

(defn sample [f sd sr n t]
  (let [s (->Sample f n t nil nil nop true true false false)]
    (set! (.-sampled-it s)
          (sd #(if (identical? nop (.-latest s))
                 (do (set! (.-latest s) nil)
                     (set! (.-sampler-busy s) (not (.-sampler-busy s)))
                     (when (.-sampler-busy s) ((.-notifier s))))
                 (do (set! (.-sampled-busy s) (not (.-sampled-busy s)))
                     (when (and (not (.-sampled-busy s)) (.-sampler-done s) (not (.-sampler-busy s)))
                       (sample-flush s))))
              #(do (set! (.-sampled-done s) true)
                   (when (and (.-sampler-done s) (not (.-sampled-busy s)))
                     ((.-terminator s))))))
    (set! (.-sampler-it s)
          (sr #(when (set! (.-sampler-busy s) (not (.-sampler-busy s)))
                 ((.-notifier s)))
              #(do ((.-sampled-it s))
                   (set! (.-sampler-done s) true)
                   (when (and (not (.-sampler-busy s)) (.-sampled-done s) (not (.-sampled-busy s)))
                     ((.-terminator s)))))) s))