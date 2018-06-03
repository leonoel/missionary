(ns missionary.impl
  (:require
    [plop.core :refer [place]]
    #?(:clj  [clojure.core.async.impl.ioc-macros :as jvm-ioc])
    #?(:clj  [cljs.core.async.impl.ioc-macros :as gcc-ioc]
       :cljs [cljs.core.async.impl.ioc-helpers :as gcc-ioc])
    #?(:clj  [clojure.tools.analyzer.jvm :as an-jvm])
    #?(:cljs [goog.async.nextTick]))
  #?(:cljs
     (:require-macros [missionary.impl :refer [safe state!]]))
  #?(:clj
     (:import (clojure.lang Agent)
              (java.util.concurrent.atomic AtomicReference AtomicReferenceArray)
              (java.util.concurrent Executor CountDownLatch ScheduledThreadPoolExecutor
                                    TimeUnit Future ScheduledExecutorService))))


;;;;;;;;;;
;; MISC ;;
;;;;;;;;;;

(defmacro safe [[error success] & failure]
  `(try ~success (catch ~(if (:js-globals &env) :default 'Throwable) ~error ~@failure)))

(defn nop
  ([])
  ([_])
  ([_ _])
  ([_ _ _])
  ([_ _ _ & _]))

(defn compel [task]
  (fn [s! f!] (task s! f!) nop))


;;;;;;;;;;;;;;;;;;;;;
;; SYNCHRONIZATION ;;
;;;;;;;;;;;;;;;;;;;;;

(defn send-rf [x !] (! x) x)

(def dfv-dereference-cancelled (ex-info "Dataflow variable dereference cancelled." {:task/cancelled ::dfv-dereference}))

(defn dfv []
  #?(:clj
     (let [state (AtomicReference. #{})]
       (fn
         ([t]
          (let [x (.get state)]
            (when (set? x)
              (if (.compareAndSet state x (reduced t))
                (do (reduce send-rf t x) nil)
                (recur t)))))
         ([s! f!]
          (let [x (.get state)]
            (if (set? x)
              (let [! #(s! %)]
                (if (.compareAndSet state x (conj x !))
                  #(let [x (.get state)]
                     (when (set? x)
                       (when (contains? x !)
                         (if (.compareAndSet state x (disj x !))
                           (f! dfv-dereference-cancelled)
                           (recur)))))
                  (recur s! f!)))
              (do (s! @x) nop))))))

     :cljs
     (place [bound false
             value nil
             watch (transient #{})]
       (fn
         ([t]
          (when-not bound
            (set! bound true)
            (set! value t)
            (reduce send-rf t (persistent! watch))
            (set! watch nil)))
         ([s! f!]
          (if bound
            (do (s! value) nop)
            (let [! #(s! %)]
              (set! watch (conj! watch !))
              #(when-not bound
                 (when (contains? watch !)
                   (set! watch (disj! watch !))
                   (f! dfv-dereference-cancelled))))))))))

(defn disj-nil [s x]
  (when-not (== 1 (count s)) (disj s x)))

(defn dissoc-nil [m x]
  (when-not (== 1 (count m)) (dissoc m x)))

(def rdv-give-cancelled (ex-info "Rendez-vous give cancelled." {:task/cancelled ::rdv-give}))
(def rdv-take-cancelled (ex-info "Rendez-vous take cancelled." {:task/cancelled ::rdv-take}))

(defn rdv []
  #?(:clj
     (let [state (AtomicReference. nil)]
       (fn
         ([t]
          (fn [s! f!]
            (let [x (.get state)]
              (if (set? x)
                (let [r (first x)]
                  (if (.compareAndSet state x (disj-nil x r))
                    (do (r t) (s! nil) nop) (recur s! f!)))
                (let [w #(s! nil)]
                  (if (.compareAndSet state x (assoc x w t))
                    #(let [x (.get state)]
                       (when (contains? x w)
                         (if (.compareAndSet state x (dissoc-nil x w))
                           (f! rdv-give-cancelled) (recur))))
                    (recur s! f!)))))))
         ([s! f!]
          (let [x (.get state)]
            (if (map? x)
              (let [[w u] (first x)]
                (if (.compareAndSet state x (dissoc-nil x w))
                  (do (w) (s! u) nop) (recur s! f!)))
              (let [r #(s! %)]
                (if (.compareAndSet state x (conj (or x #{}) r))
                  #(let [x (.get state)]
                     (when (contains? x r)
                       (if (.compareAndSet state x (disj-nil x r))
                         (f! rdv-take-cancelled) (recur))))
                  (recur s! f!))))))))

     :cljs
     (place [readers #{}
             writers {}]
       (fn
         ([t]
          (fn [s! f!]
            (if-some [[!] (seq readers)]
              (do (set! readers (disj readers !))
                  (! t) (s! nil) nop)
              (let [! #(s! nil)]
                (set! writers (assoc writers ! t))
                #(when (contains? writers !)
                   (set! writers (dissoc writers !))
                   (f! rdv-give-cancelled))))))
         ([s! f!]
          (if-some [[[! t]] (seq writers)]
            (do (set! writers (dissoc writers !))
                (!) (s! t) nop)
            (let [! #(s! %)]
              (set! readers (conj readers !))
              #(when (contains? readers !)
                 (set! readers (disj readers !))
                 (f! rdv-take-cancelled)))))))))

(def buf-dequeue-cancelled (ex-info "Buffer dequeue cancelled." {:task/cancelled ::buf-dequeue}))

(defn buf []
  #?(:clj
     (let [state (AtomicReference. nil)]
       (fn
         ([t]
          (let [x (.get state)]
            (if (set? x)
              (let [r (first x)]
                (if (.compareAndSet state x (disj-nil x r)) (r t) (recur t)))
              (when-not (.compareAndSet state x (conj (or x []) t)) (recur t)))))
         ([s! f!]
          (let [x (.get state)]
            (if (vector? x)
              (if (.compareAndSet state x (when-not (== 1 (count x)) (subvec x 1)))
                (do (s! (nth x 0)) nop) (recur s! f!))
              (let [r #(s! %)]
                (if (.compareAndSet state x (conj (or x #{}) r))
                  #(let [x (.get state)]
                     (when (contains? x r)
                       (if (.compareAndSet state x (disj-nil x r))
                         (f! buf-dequeue-cancelled) (recur))))
                  (recur s! f!))))))))

     :cljs
     (place [enqueue (array)
             dequeue (array)
             readers #{}]
       (fn
         ([t]
          (if-some [[!] (seq readers)]
            (do (set! readers (disj readers !)) (! t))
            (do (.push enqueue t) nil)))
         ([s! f!]
          (if (zero? (alength dequeue))
            (if (zero? (alength enqueue))
              (let [! #(s! %)]
                (set! readers (conj readers !))
                #(when (contains? readers !)
                   (set! readers (disj readers !))
                   (f! buf-dequeue-cancelled)))
              (let [tmp enqueue]
                (set! enqueue dequeue)
                (set! dequeue (.reverse tmp))
                (s! (.pop tmp)) nop))
            (do (s! (.pop dequeue)) nop)))))))

(def sem-acquire-cancelled (ex-info "Semaphore acquire cancelled." {:task/cancelled ::sem-acquire}))

(defn sem
  ([] (sem 1))
  ([n]
   #?(:clj
      (let [state (AtomicReference. n)]
        (fn
          ([]
           (let [x (.get state)]
             (if (set? x)
               (let [r (first x)]
                 (if (.compareAndSet state x (disj-nil x r)) (r) (recur)))
               (when-not (.compareAndSet state x (if (nil? x) 1 (inc x))) (recur)))))
          ([s! f!]
           (let [x (.get state)]
             (if (number? x)
               (if (.compareAndSet state x (if (== 1 x) nil (dec x)))
                 (do (s! nil) nop) (recur s! f!))
               (let [r #(s! nil)]
                 (if (.compareAndSet state x (conj (or x #{}) r))
                   #(let [x (.get state)]
                      (when (contains? x r)
                        (if (.compareAndSet state x (disj-nil x r))
                          (f! sem-acquire-cancelled) (recur))))
                   (recur s! f!))))))))

      :cljs
      (place [available n
              readers #{}]
        (fn
          ([]
           (if-some [[!] (seq readers)]
             (do (set! readers (disj readers !)) (!))
             (do (set! available (inc available)) nil)))
          ([s! f!]
           (if (zero? available)
             (let [! #(s! nil)]
               (set! readers (conj readers !))
               #(when (contains? readers !)
                  (set! readers (disj readers !))
                  (f! sem-acquire-cancelled)))
             (do (set! available (dec available))
                 (s! nil) nop))))))))

(defmacro holding [lock & body]
  `(let [l# ~lock] (missionary.core/? l#) (try ~@body (finally (l#)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PARALLEL COMPOSITION ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn call-rf [x !] (!) x)

(defn join
  ([] (fn [s! _] (s! []) nop))
  ([& ts]
   (fn [s! f!]
     #?(:clj
        (let [n (count ts)
              a (object-array n)
              s (AtomicReference. [n n nil])
              c (->> ts
                     (into []
                           (map-indexed
                             (fn [i t]
                               (t (fn [x]
                                    (aset a i x)
                                    (loop []
                                      (let [old (.get s)
                                            err (nth old 1)
                                            rem (dec (nth old 0))]
                                        (if (.compareAndSet s old (assoc old 0 rem))
                                          (when (zero? rem)
                                            (if (== n err)
                                              (s! (vec a))
                                              (f! (aget a err))))
                                          (recur)))))
                                  (fn [e]
                                    (aset a i e)
                                    (loop []
                                      (let [old (.get s)
                                            err (nth old 1)
                                            rem (dec (nth old 0))]
                                        (if (== n err)
                                          (if (.compareAndSet s old (assoc old 0 rem 1 i))
                                            (if (zero? rem)
                                              (f! e)
                                              (when-some [c (nth old 2)] (c)))
                                            (recur))
                                          (if (.compareAndSet s old (assoc old 0 rem))
                                            (when (zero? rem) (f! (aget a err)))
                                            (recur))))))))))
                     (partial reduce call-rf nil))]
          (loop []
            (let [old (.get s)
                  rem (nth old 0)
                  err (nth old 1)]
              (if (zero? rem)
                nop
                (if (.compareAndSet s old (assoc old 2 c))
                  (if (== n err) c (do (c) nop))
                  (recur))))))

        :cljs
        (place [results (object-array (count ts))
                pending (alength results)
                failure (alength results)
                cancel! (->> ts
                             (into [] (map-indexed
                                        (fn [i t]
                                          (t (fn [x]
                                               (aset results i x)
                                               (set! pending (dec pending))
                                               (when (zero? pending)
                                                 (if (== (alength results) failure)
                                                   (s! (vec results))
                                                   (f! (aget results failure)))))
                                             (fn [e]
                                               (aset results i e)
                                               (set! pending (dec pending))
                                               (if (== (alength results) failure)
                                                 (do (set! failure i)
                                                     (if (zero? pending)
                                                       (f! e)
                                                       (when (some? cancel!) (cancel!))))
                                                 (when (zero? pending) (f! (aget results failure)))))))))
                             (partial reduce call-rf nil))]
          (if (zero? pending)
            nop (if (== (alength results) failure)
                  cancel! (do (cancel!) nop))))))))

(defn race-failure [errors]
  (ex-info "Race failure." {::errors errors}))

(defn race [& ts]
  (let [j (->> ts (map (fn [t] (fn [s f] (t f s)))) (apply join))]
    (fn [s f] (j (comp f race-failure) s))))



;;;;;;;;;;;;
;; TIMING ;;
;;;;;;;;;;;;

#?(:clj (def scheduler (ScheduledThreadPoolExecutor. 1)))

(defn sleep
  ([t] (sleep t nil))
  ([t x]
    #?(:clj
       (fn [s! f!]
         (place [^boolean pending true]
           (let [fut (.schedule ^ScheduledExecutorService scheduler
                                (reify Runnable (run [_] (set! pending false) (s! x)))
                                (long t) TimeUnit/MILLISECONDS)]
             #(when (.cancel ^Future fut false)
                (when pending (f! (ex-info "Sleep cancelled." {:task/cancelled ::sleep})))))))
       :cljs
       (fn [s! f!]
         (place [^boolean pending true]
           (let [id (js/setTimeout #(do (set! pending false) (s! x)) t)]
             #(when pending
                (set! pending false)
                (js/clearTimeout id)
                (f! (ex-info "Sleep cancelled." {:task/cancelled ::sleep})))))))))

(defn yielding [x] #(do x))

(defn throwing [e] #(throw e))

(defn attempt [t]
  (fn [s _] (t (comp s yielding) (comp s throwing))))

(defn absolve [t]
  (fn [s f] (t (fn [a] (safe [e (s (a))] (f e))) nil)))

(defn timeout [delay task]
  (->> task
       (attempt)
       (race (sleep delay #(throw (ex-info "Task timed out." {::delay delay, ::task task}))))
       (absolve)))


;;;;;;;;;;;;;;;
;; THREADING ;;
;;;;;;;;;;;;;;;

(def threading-unsupported (ex-info "Threading operations are not supported." {}))

(defmacro off [& body]
  (if (:js-globals &env)
    (throw threading-unsupported)
    `(fn [s!# f!#]
       (let [run# #(safe [e# (s!# (do ~@body))] (f!# e#))
             fut# (.submit Agent/soloExecutor ^Runnable run#)]
         #(.cancel fut# true)))))

(def process-cancelled (ex-info "Process cancelled." {:task/cancelled ::process}))

(defn ?
  ([]
    #?(:clj  (when (.isInterrupted (Thread/currentThread))
               (throw process-cancelled))
       :cljs (throw threading-unsupported)))
  ([t]
    #?(:clj
       (place [f nil
               l (CountDownLatch. 1)
               c (t (fn [x] (set! f (yielding x)) (.countDown l))
                    (fn [e] (set! f (throwing e)) (.countDown l)))]
         (try (.await l) (f)
              (catch InterruptedException _
                (c) (.await l)
                (.interrupt (Thread/currentThread)) (f))))
       :cljs
       (throw threading-unsupported))))


;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SEQUENTIAL PROCESSES ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const slot-handler 0)
(def ^:const slot-block 1)
(def ^:const slot-value 2)
(def ^:const slot-bindings 3)
(def ^:const slot-frames 4)
(def ^:const slot-exception 5)
(def ^:const slot-return 6)
(def ^:const slot-count 7)

(defmacro state-holder [size]
  (if (:js-globals &env)
    `(object-array ~size)
    `(AtomicReferenceArray. (int ~size))))

(defmacro state!
  ([array slot]
   (if (:js-globals &env)
     `(aget ~array ~slot)
     `(.get ^AtomicReferenceArray ~array ~slot)))
  ([array slot value]
   (if (:js-globals &env)
     `(aset ~array ~slot ~value)
     `(.set ^AtomicReferenceArray ~array ~slot ~value))))

(defn process [ctor]
  (fn [s! f!]
    #?(:clj
       (let [state (AtomicReference. nop)]
         (ctor s! f!
               (fn
                 ([] (nil? (.get state)))
                 ([cancel]
                  (if-some [! (.get state)]
                    (when-not (.compareAndSet state ! cancel) (recur cancel))
                    (cancel)))))
         #(when-some [! (.getAndSet state nil)] (!)))
       :cljs
       (place [state nop]
         (ctor s! f!
               (fn
                 ([] (nil? state))
                 ([cancel]
                  (if (nil? state)
                    (cancel)
                    (set! state cancel)))))
         #(when-some [! state] (set! state nil) (!))))))

(defn scheduled [f]
  #?(:clj #(.execute Agent/pooledExecutor f)
     :cljs #(goog.async.nextTick f)))

(defn recover [state error]
  #?(:clj
     (if-some [[f & fs] (seq (state! state slot-frames))]
       (do (state! state slot-value error)
           (state! state slot-block f)
           (state! state slot-frames fs)
           :recur)
       (throw error))

     :cljs
     (do (state! state slot-exception error)
         (gcc-ioc/process-exception state)
         :recur)))

(defn ioc-sp [init size step]
  (process
    (fn [s! f! c!]
      (let [state (state-holder size)
            run (scheduled
                  #(while (safe [e (step state)]
                            (safe [e (recover state e)]
                              (f! e) nil))))
            scb (fn [x]
                  (state! state slot-value x)
                  (run))
            fcb (fn [e]
                  (state! state slot-value e)
                  (state! state slot-block -1)
                  (run))]
        (state! state slot-block init)
        (state! state slot-return s!)
        (state! state slot-handler
                (fn
                  ([] (c!))
                  ([task] (c! (task scb fcb)))))
        (run)))))

(defn ioc-?
  ([state blk]
   (if ((state! state slot-handler))
     (do (state! state slot-value process-cancelled)
         (state! state slot-block -1))
     (do (state! state slot-value nil)
         (state! state slot-block blk)))
    :recur)
  ([state blk task]
   (state! state slot-block blk)
   ((state! state slot-handler) task) nil))

(defn ioc-return [state value]
  ((state! state slot-return) value) nil)

#?(:clj
   (def transitions
     {'?                 `ioc-?
      'missionary.core/? `ioc-?
      'missionary.impl/? `ioc-?
      :Return            `ioc-return}))

#?(:clj
   (defn index-by [f blocks]
     (reduce
       (fn [idx [blk-id blk]]
         (reduce
           (fn [idx inst]
             (reduce
               (fn [idx sym]
                 (update idx sym (fnil conj #{}) blk-id))
               idx (f inst)))
           idx blk))
       {} blocks)))

#?(:clj
   (defn state-machine [reads-from
                        writes-to
                        emit-instruction
                        terminate-block
                        instruction?
                        machine]
     (let [blocks (:blocks machine)
           read-in (index-by #(filter instruction? (reads-from %)) blocks)
           written-in (index-by #(filter instruction? (writes-to %)) blocks)
           slots (->> (concat (keys read-in) (keys written-in))
                      (filter (fn [sym]
                                (or (not= (read-in sym)
                                          (written-in sym))
                                    (-> read-in sym count (> 1)))))
                      (reduce
                        (fn [slots sym]
                          (if (contains? slots sym)
                            slots (assoc slots sym (+ slot-count (count slots))))) {}))
           state-sym (gensym "state_")]
       `(ioc-sp
          ~(:start-block machine)
          ~(+ slot-count (count slots))
          (fn [~state-sym]
            (case (int (state! ~state-sym slot-block))
              ~@(into [] (mapcat (fn [[id blk]]
                                   [id `(let [~@(into []
                                                      (comp
                                                        (mapcat reads-from)
                                                        (filter slots)
                                                        (distinct)
                                                        (mapcat (fn [sym] [sym (list `state! state-sym (slots sym))])))
                                                      blk)
                                              ~@(into [] (mapcat #(emit-instruction % state-sym)) (butlast blk))]
                                          ~@(into []
                                                  (comp
                                                    (mapcat writes-to)
                                                    (filter slots)
                                                    (distinct)
                                                    (map (fn [sym] (list `state! state-sym (slots sym) sym))))
                                                  blk)
                                          ~(terminate-block (last blk) state-sym transitions))])) blocks)
              (throw (state! ~state-sym slot-value))))))))

#?(:clj
   (defmacro sp [& body]
     (if (:js-globals &env)
       (state-machine gcc-ioc/reads-from
                      gcc-ioc/writes-to
                      gcc-ioc/emit-instruction
                      gcc-ioc/terminate-block
                      gcc-ioc/instruction?
                      (second (gcc-ioc/parse-to-state-machine body &env transitions)))
       (let [crossing-env (zipmap (keys &env) (repeatedly (partial gensym "env_")))
             [_ machine] (binding [an-jvm/run-passes jvm-ioc/run-passes]
                           (-> (an-jvm/analyze
                                 `(let [~@(if (jvm-ioc/nested-go? &env)
                                            (mapcat (fn [[l {:keys [tag]}]]
                                                      (jvm-ioc/emit-hinted l tag crossing-env)) &env)
                                            (mapcat (fn [[l ^clojure.lang.Compiler$LocalBinding lb]]
                                                      (jvm-ioc/emit-hinted l (when (.hasJavaClass lb)
                                                                               (some-> lb .getJavaClass .getName))
                                                                           crossing-env)) &env))]
                                    ~@body)
                                 (jvm-ioc/make-env &env crossing-env)
                                 {:passes-opts (merge an-jvm/default-passes-opts
                                                      {:uniquify/uniquify-env        true
                                                       :mark-transitions/transitions transitions})})
                               (jvm-ioc/parse-to-state-machine transitions)))]
         `(let [~@(mapcat (fn [[l sym]] [sym `(^:once fn* [] ~(vary-meta l dissoc :tag))]) crossing-env)]
            ~(state-machine jvm-ioc/reads-from
                            jvm-ioc/writes-to
                            jvm-ioc/emit-instruction
                            jvm-ioc/terminate-block
                            jvm-ioc/instruction?
                            machine))))))
