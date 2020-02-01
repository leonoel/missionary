(ns ^:no-doc missionary.impl)

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
      (throw (ex-info "Process cancelled." {:cancelled :missionary/sp}))))
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
      (throw (ex-info "Process cancelled." {:cancelled :missionary/ap}))))
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

(defn ap-emit [^Gather g x]
  (set! (.-current g) x)
  (let [^Ambiguous p (.-process g)]
    (if (identical? nop (.-queue p))
      (do (set! (.-queue p) nil)
          (set! (.-next g) nil)
          (set! (.-head p) g)
          ((.-notifier p)))
      (do (set! (.-next g) (.-queue p))
          (set! (.-queue p) g)))))

(defn ap-step [^Gather g]
  (let [pf fiber-current]
    (set! fiber-current g)
    (loop []
      (try
        (let [x ((.-coroutine g))]
          (when-not (identical? x nop)
            (ap-emit g x)))
        (catch :default e
          (set! (.-failed g) true)
          (ap-emit g e)))
      (when (set! (.-busy g) (not (.-busy g)))
        (recur)))
    (set! fiber-current pf)))

(defn ap-cancel [^Gather g]
  (when-some [t (.-token g)]
    (set! (.-token g) nil) (t)))

(defn ap-gather [^Ambiguous p]
  (let [g (->Gather p nil false nil nil nil nil nil nop false)]
    (set! (.-resume g)
          (fn [x]
            (set! (.-current g) x)
            (when (set! (.-busy g) (not (.-busy g)))
              (ap-step g))))
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
                     (set! (.-failed g) true)
                     (set! (.-current g) e)
                     (when (set! (.-busy g) (not (.-busy g))) (ap-step g))
                     false)))))
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
    (let [b (.-sampled-busy s)]
      (set! (.-sampled-busy s) (not b))
      (when b (recur)))))

(defn sample-deref [^Sample s]
  (let [d (not (.-sampled-busy s))]
    (try (when d (set! (.-latest s) @(.-sampled-it s)))
         (let [x @(.-sampler-it s)]
           (when (identical? nop (.-latest s))
             (throw (js/Error. "Unable to sample : flow is not ready.")))
           ((.-combinator s) (.-latest s) x))
         (catch :default e
           (set! (.-latest s) nil)
           (set! (.-combinator s) {})
           (set! (.-notifier s) #(try (sample-deref s) (catch :default _)))
           (sample-cancel s)
           (throw e))
         (finally
           (when d (set! (.-sampled-busy s) (not (.-sampled-busy s))))
           (if (set! (.-sampler-busy s) (not (.-sampler-busy s)))
             (when (.-sampler-done s)
               (if (.-sampled-done s)
                 ((.-terminator s))
                 (when-not (.-sampled-busy s)
                   (sample-flush s))))
             ((.-notifier s)))))))

(defn sample [f sd sr n t]
  (let [s (->Sample f n t nil nil nop true true false false)]
    (set! (.-sampled-it s)
          (sd #(do (set! (.-sampled-busy s) (not (.-sampled-busy s)))
                   (when (and (.-sampled-busy s) (.-sampler-done s) (.-sampler-busy s))
                     (sample-flush s)))
              #(do (set! (.-sampled-done s) true)
                   (when (and (.-sampler-done s) (.-sampler-busy s))
                     ((.-terminator s))))))
    (set! (.-sampler-it s)
          (sr #(let [b (.-sampler-busy s)]
                 (set! (.-sampler-busy s) (not b))
                 (when b ((.-notifier s))))
              #(do ((.-sampled-it s))
                   (set! (.-sampler-done s) true)
                   (when (.-sampler-busy s)
                     (if (.-sampled-done s)
                       ((.-terminator s))
                       (when-not (.-sampled-busy s)
                         (sample-flush s))))))) s))


;;;;;;;;;;;;;
;; REACTOR ;;
;;;;;;;;;;;;;

(def ^:const PENDING 0)
(def ^:const READY 1)
(def ^:const DONE 2)

(declare dag-cancel)
(deftype Dag [^number reentrancy success failure result ^boolean failed ^boolean cancelled ^number tier
              current emitter today tomorrow head tail]
  IFn (-invoke [_] (dag-cancel _)))

(declare pub-cancel)
(declare pub-sub)
(deftype Pub [^Dag dag ^boolean discrete id iterator value ^boolean cancelled ^boolean scheduled
              ^number tier ^number state ^number pressure prev next head tail child sibling]
  IFn
  (-invoke [_] (pub-cancel _))
  (-invoke [_ n t] (pub-sub _ n t)))

(declare sub-cancel)
(declare sub-deref)
(deftype Sub [^Pub up ^Pub down notifier terminator ^number state ^Sub prev ^Sub next]
  IFn
  (-invoke [_] (sub-cancel _))
  IDeref
  (-deref [_] (sub-deref _)))

(def dag-current)

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
        (< xl yl)))))

(defn pub-link [^Pub x ^Pub y]
  (if (lt (.-id x) (.-id y))
    (do (set! (.-sibling y) (.-child x))
        (set! (.-child x) y) x)
    (do (set! (.-sibling x) (.-child y))
        (set! (.-child y) x) y)))

(defn pub-schedule [^Pub p]
  (let [^Dag dag (.-dag p)]
    (when-not (.-scheduled p)
      (set! (.-scheduled p) true)
      (if (when-some [^Pub e (.-emitter dag)] (lt (.-id e) (.-id p)))
        (set! (.-today dag) (if-some [t (.-today dag)] (pub-link p t) p))
        (set! (.-tomorrow dag) (if-some [t (.-tomorrow dag)] (pub-link p t) p))))))

(defn pub-more [^Pub pub]
  (let [^Dag dag (.-dag pub)
        ^Pub prv (.-current dag)]
    (set! (.-current dag) pub)
    (set! (.-state pub) PENDING)
    (set! (.-value pub)
          (try @(.-iterator pub)
               (catch :default e
                 (when-not (.-failed dag)
                   (set! (.-failed dag) true)
                   (set! (.-result dag) e)
                   (dag)) nop)))
    (set! (.-current dag) prv)))

(defn sub-push [^Sub sub]
  (set! (.-state sub) READY)
  (set! (.-pressure (.-up sub)) (inc (.-pressure (.-up sub))))
  ((.-notifier sub)))

(defn sub-pull [^Sub sub]
  (set! (.-state sub) PENDING)
  (set! (.-pressure (.-up sub)) (dec (.-pressure (.-up sub))))
  (pub-schedule (.-up sub)))

(defn dag-acquire [^Dag dag]
  (set! (.-reentrancy dag) (inc (.-reentrancy dag)))
  (let [prv dag-current] (set! dag-current dag) prv))

(defn dag-release [^Dag dag ^Dag prv]
  (when (== 1 (.-reentrancy dag))
    (while (some? (set! (.-emitter dag) (.-tomorrow dag)))
      (set! (.-tomorrow dag) nil)
      (while
        (let [emit (.-emitter dag)]
          (set! (.-today dag)
                (loop [heap nil
                       prev nil
                       head (.-child emit)]
                  (if (nil? head)
                    (if (nil? prev) heap (if (nil? heap) prev (pub-link heap prev)))
                    (let [next (.-sibling head)]
                      (set! (.-sibling head) nil)
                      (if (nil? prev)
                        (recur heap head next)
                        (let [head (pub-link prev head)]
                          (recur (if (nil? heap) head (pub-link heap head)) nil next)))))))
          (set! (.-child emit) nil)
          (set! (.-scheduled emit) false)
          (when (.-discrete emit) (set! (.-value emit) nop))
          (when-not (== (.-state emit) PENDING)
            (when-not (and (.-discrete emit) (pos? (.-pressure emit)))
              (if (== (.-state emit) READY)
                (do (if (or (.-discrete emit) (.-cancelled emit))
                      (do (pub-more emit) (pub-schedule emit))
                      (set! (.-value emit) nil))
                    (loop [^Sub sub (.-head emit)]
                      (when (some? sub)
                        (when (== (.-state sub) PENDING)
                          (let [pub (.-current dag)]
                            (set! (.-current dag) (.-down sub))
                            (sub-push sub)
                            (set! (.-current dag) pub)
                            (recur (.-next sub)))))))
                (do (loop [^Sub sub (.-head emit)]
                      (when (some? sub) (sub) (recur (.-next sub))))
                    (if (nil? (.-prev emit))
                      (set! (.-head dag) (.-next emit))
                      (set! (.-next (.-prev emit)) (.-next emit)))
                    (if (nil? (.-next emit))
                      (set! (.-tail dag) (.-prev emit))
                      (set! (.-prev (.-next emit)) (.-prev emit)))))))
          (some? (set! (.-emitter dag) (.-today dag))))))
    (when (nil? (.-head dag)) ((if (.-failed dag) (.-failure dag) (.-success dag)) (.-result dag))))
  (set! dag-current prv)
  (set! (.-reentrancy dag) (dec (.-reentrancy dag))))

(defn dag-cancel [^Dag dag]
  (let [p (dag-acquire dag)]
    (when-not (.-cancelled dag)
      (set! (.-cancelled dag) true)
      (loop [^Pub pub (.-head dag)]
        (when (some? pub)
          (pub) (recur (.-next pub)))))
    (dag-release dag p)))

(defn pub-cancel [^Pub pub]
  (let [p (dag-acquire (.-dag pub))]
    (when-not (.-cancelled pub)
      (set! (.-cancelled pub) true)
      (pub-schedule pub)
      ((.-iterator pub)))
    (dag-release (.-dag pub) p)))

(defn pub-sub [^Pub up n t]
  (let [^Dag dag (.-dag up)
        ^Dag prv (dag-acquire dag)
        ^Pub down (.-current dag)]
    (assert (some? down))
    (assert (lt (.-id up) (.-id down)))
    (let [sub (->Sub up down n t PENDING nil nil)]
      (if (== (.-state up) DONE)
        (t) (do (set! (.-prev sub) (.-tail up))
                (set! (.-tail up) sub)
                (if-some [s (.-prev sub)]
                  (set! (.-next s) sub)
                  (set! (.-head up) sub))
                (when-not (identical? nop (.-value up))
                  (when-not (and (.-scheduled up) (lt (.-id (.-emitter dag)) (.-id up)))
                    (sub-push sub)))))
      (dag-release dag prv) sub)))

(defn sub-cancel [^Sub sub]
  (let [^Pub u (.-up sub)
        ^Dag dag (.-dag u)
        ^Dag prv (dag-acquire dag)]
    (when-not (== (.-state sub) DONE)
      (if (== (.-state sub) READY)
        (sub-pull sub) ((.-terminator sub)))
      (set! (.-state sub) DONE)
      (if-some [p (.-prev sub)]
        (set! (.-next p) (.-next sub))
        (set! (.-head u) (.-next sub)))
      (if-some [n (.-next sub)]
        (set! (.-prev n) (.-prev sub))
        (set! (.-tail u) (.-prev sub))))
    (dag-release dag prv)))

(defn sub-deref [^Sub sub]
  (let [^Pub u (.-up sub)
        ^Dag dag (.-dag u)
        ^Dag prv (dag-acquire dag)]
    (when (== (.-state u) READY) (pub-more u))
    (let [x (.-value u)]
      (if (== (.-state sub) DONE)
        ((.-terminator sub)) (sub-pull sub))
      (dag-release dag prv)
      (when (identical? x nop) (throw (js/Error. "No such element.")))
      x)))

(defn dag [i s f]
  (let [^Dag dag (->Dag 0 s f nil false false 0 nil nil nil nil nil nil)
        ^Dag prv (dag-acquire dag)]
    (set! (.-result dag)
          (try (i) (catch :default e
                     (set! (.-failed dag) true)
                     (dag) e)))
    (dag-release dag prv) dag))

(defn pub [f d]
  (let [^Dag dag (doto dag-current (assert "Unable to publish : not in reactor."))
        ^Pub pub (->Pub dag d (if-some [^Pub cur (.-current dag)]
                                (let [n (alength (.-id cur))
                                      a (make-array (inc n))]
                                  (dotimes [i n] (aset a i (aget (.-id cur) i)))
                                  (doto a (aset n (doto (.-tier cur) (->> (inc) (set! (.-tier cur)))))))
                                (doto (make-array 1) (aset 0 (doto (.-tier dag) (->> (inc) (set! (.-tier dag)))))))
                        nil nop false false 0 0 0 (.-tail dag) nil nil nil nil nil)]
    (set! (.-tail dag) pub)
    (if-some [^Pub prv (.-prev pub)]
      (set! (.-next prv) pub)
      (set! (.-head dag) pub))
    (let [prv (.-current dag)]
      (set! (.-current dag) pub)
      (set! (.-iterator pub)
            (f #(let [prv (dag-acquire dag)]
                  (set! (.-state pub) READY)
                  (pub-schedule pub)
                  (dag-release dag prv))
               #(let [prv (dag-acquire dag)]
                  (set! (.-state pub) DONE)
                  (pub-schedule pub)
                  (dag-release dag prv))))
      (when (.-cancelled dag) (pub))
      (set! (.-current dag) prv) pub)))