(ns missionary.conc
  "Concurrency test framework for missionary flows.
   Generates protocol-valid scenarios by construction via process borrowing."
  (:require [clojure.string :as str])
  (:import (clojure.lang IDeref IFn)
           (java.util.concurrent
            LinkedBlockingQueue SynchronousQueue TimeUnit)
           (java.util.concurrent.atomic AtomicInteger)
           (missionary ProtocolViolation)))

(set! *warn-on-reflection* true)

(defn find-index-by [pred x*]
  (transduce (keep-indexed (fn [idx x] (when (pred x) idx))) (fn ([v] v) ([_ac nx] (reduced nx))) nil x*))

(defn find-by [pred x*]
  (transduce (keep (fn [x] (when (pred x) x))) (fn ([v] v) ([_ac nx] (reduced nx))) nil x*))

(defn keep-if [x pred] (when (pred x) x))

;; ── S1: ProcessDebug Protocol ─────────────────────────────────────

(defprotocol ProcessDebug
  (valid-ops [this])
  (terminal? [this])
  (dbg-state [this]))

;; ── S2: DummyFlow ─────────────────────────────────────────────────

(def ^:private ^:const DF-STEPPED   1)
(def ^:private ^:const DF-DONE      2)
(def ^:private ^:const DF-CANCELLED 4)
(def ^:private ^:const DF-CRASHED   8)

(defn- transfer-value
  "Returns 0 1 1 2 3 3 4 5 5 ... — odd numbers repeated to exercise equality-based work skipping."
  [tc]
  (+ (* 2 (quot tc 3)) (if (zero? (mod tc 3)) 0 1)))

(defn- ->dummy-state []
  (let [s (object-array [false 0 0 nil nil])]
    [(fn should-throw  ([] (aget s (int 0))) ([x] (aset s (int 0) x)))
     (fn re-step-count ([] (aget s (int 1))) ([x] (aset s (int 1) x)))
     (fn xfer-count    ([] (aget s (int 2))) ([x] (aset s (int 2) x)))
     (fn step-cb       ([] (aget s (int 3))) ([x] (aset s (int 3) x)))
     (fn done-cb       ([] (aget s (int 4))) ([x] (aset s (int 4) x)))]))

(defn ->dummy-flow
  ([] (->dummy-flow {}))
  ([{:keys [step-on-init weights value-fn]
     :or   {step-on-init true
            weights      {:step 40 :done 5 :crash 10 :re-step 10}
            value-fn     transfer-value}}]
   (let [state         (AtomicInteger. 0)
         [should-throw re-step-count xfer-count step-cb done-cb] (->dummy-state)
         do-step       (fn []
                         (loop []
                           (let [old (.get state)]
                             (if (pos? (bit-and old (bit-or DF-STEPPED DF-DONE DF-CRASHED)))
                               nil
                               (if (.compareAndSet state old (bit-or old DF-STEPPED))
                                 (do ((step-cb)) nil)
                                 (recur))))))
         do-done       (fn []
                         (loop []
                           (let [old (.get state)]
                             (cond
                               (pos? (bit-and old DF-DONE))                    nil
                               (pos? (bit-and old DF-STEPPED))                 nil
                               (.compareAndSet state old (bit-or old DF-DONE)) (do ((done-cb)) nil)
                               :else                                           (recur)))))
         do-crash      (fn [] (should-throw true) nil)
         do-re-step    (fn [] (-> (re-step-count) inc (re-step-count)) nil)]
     (reify
       ProcessDebug
       (valid-ops [_]
         (if (nil? (step-cb))
           [] ;; not spawned yet — flow hasn't been invoked
           (let [s        (.get state)
                 stepped? (pos? (bit-and s DF-STEPPED))
                 done?    (pos? (bit-and s DF-DONE))
                 crashed? (pos? (bit-and s DF-CRASHED))]
             (cond-> []
               (and (not stepped?) (not done?) (not crashed?))
               (conj {:op :step :weight (:step weights) :exec do-step})
               (and (not done?) (not stepped?))
               (conj {:op :done :weight (if (pos? (bit-and s DF-CANCELLED)) 200 (:done weights)) :exec do-done})
               (and (not done?) (not crashed?))
               (conj {:op     :crash
                      :weight (cond-> (:crash weights) stepped? (* 2))
                      :exec   do-crash})
               (and (not done?) (not crashed?))
               (conj {:op :re-step :weight (:re-step weights) :exec do-re-step})))))
       (terminal? [_]
         (pos? (bit-and (.get state) DF-DONE)))
       (dbg-state [_]
         (let [s (.get state)]
           {:raw         s
            :stepped     (pos? (bit-and s DF-STEPPED))
            :done        (pos? (bit-and s DF-DONE))
            :cancelled   (pos? (bit-and s DF-CANCELLED))
            :crashed     (pos? (bit-and s DF-CRASHED))
            :throw-armed (should-throw)
            :re-step     (re-step-count)}))
       IFn
       (invoke [_ step done]
         (step-cb step)
         (done-cb done)
         (when step-on-init
           (loop []
             (let [old (.get state)]
               (when-not (.compareAndSet state old (bit-or old DF-STEPPED))
                 (recur))))
           ((step-cb)))
         (reify
           IFn
           (invoke [_]
             (loop []
               (let [old (.get state)]
                 (when-not (.compareAndSet state old (bit-or old DF-CANCELLED))
                   (recur))))
             nil)
           IDeref
           (deref [_]
             (if (should-throw)
               (do (loop []
                     (let [old (.get state)]
                       (when-not (.compareAndSet state old
                                                 (bit-or (bit-and old (bit-not DF-STEPPED)) DF-CRASHED))
                         (recur))))
                   (throw (ex-info "intended crash" {})))
               (let [tc    (-> (xfer-count) inc (xfer-count))
                     value (value-fn (dec tc))]
                 (loop []
                   (let [old (.get state)]
                     (when-not (.compareAndSet state old (bit-and old (bit-not DF-STEPPED)))
                       (recur))))
                 ;; Re-step: fire step during transfer (consecutive transfer)
                 (when (pos? (re-step-count))
                   (-> (re-step-count) dec (re-step-count))
                   (do-step))
                 value)))))))))

;; ── S3: Root Consumer ─────────────────────────────────────────────

(def ^:private ^:const RT-TRANSFERRED 0)
(def ^:private ^:const RT-STEPPED 1)
(def ^:private ^:const RT-CLAIMED 2)
(def ^:private ^:const RT-SDT 3)        ; SteppedDuringTransfer
(def ^:private ^:const RT-DONE 4)

(defn- ->root-state []
  (let [s (object-array [0 nil])]
    [(fn cancel-count ([] (aget s (int 0))) ([x] (aset s (int 0) x)))
     (fn iterator     ([] (aget s (int 1))) ([x] (aset s (int 1) x)))]))

(defn ->root
  [flow]
  (let [state      (AtomicInteger. RT-TRANSFERRED)
        [cancel-count iterator] (->root-state)
        step-fn    (fn []
                     (.getAndUpdate state
                                    (reify java.util.function.IntUnaryOperator
                                      (applyAsInt [_ s]
                                        (case s
                                          0 RT-STEPPED
                                          1 RT-STEPPED ;; idempotent — enforcer catches double-step
                                          2 RT-SDT
                                          3 RT-SDT     ;; idempotent
                                          4 RT-DONE))))
                     nil)
        done-fn    (fn []
                     (.getAndUpdate state
                                    (reify java.util.function.IntUnaryOperator
                                      (applyAsInt [_ s] RT-DONE)))
                     nil)
        iter       (flow step-fn done-fn)]
    (iterator iter)
    (let [transfer-fn (fn []
                        (when-not (.compareAndSet state RT-STEPPED RT-CLAIMED)
                          (throw (AssertionError.
                                  (str "Root transfer in invalid state: " (.get state)))))
                        (let [update-state!
                              (fn []
                                (.getAndUpdate state
                                               (reify java.util.function.IntUnaryOperator
                                                 (applyAsInt [_ post]
                                                   (case post
                                                     2 RT-TRANSFERRED
                                                     3 RT-STEPPED
                                                     4 RT-DONE
                                                     (throw (AssertionError.
                                                             (str "Root post-transfer state: " post))))))))
                              ret (try @(iterator)
                                       (catch Throwable e
                                         (update-state!)
                                         (throw e)))]
                          (update-state!)
                          ret))
          cancel-fn (fn []
                      ((iterator))
                      (cancel-count (inc (cancel-count)))
                      nil)
          root-dbg (fn [] {:raw (.get state)
                           :state (case (.get state)
                                    0 :transferred 1 :stepped 2 :claimed
                                    3 :sdt 4 :done (.get state))
                           :cancel-count (cancel-count)})]
      (reify ProcessDebug
        (valid-ops [_]
          (cond-> []
            (= (.get state) RT-STEPPED) (conj {:op :transfer :weight 40 :exec transfer-fn})
            (and (not= (.get state) RT-DONE) (< (cancel-count) 3))
            (conj {:op :cancel :weight (if (pos? (cancel-count)) 50 5) :exec cancel-fn})))
        (terminal? [_] (= (.get state) RT-DONE))
        (dbg-state [_] (root-dbg)))
      #_[(reify ProcessDebug
           (valid-ops [_]
             (if (= (.get state) RT-STEPPED)
               [{:op :transfer :weight 40 :exec transfer-fn}]
               []))
           (terminal? [_] (= (.get state) RT-DONE))
           (dbg-state [_] (root-dbg)))
         (reify ProcessDebug
           (valid-ops [_]
             (if (= (.get state) RT-DONE)
               []
               [{:op :cancel
                 :weight (if (cancelled) 50 5)
                 :exec cancel-fn}]))
           (terminal? [_] (= (.get state) RT-DONE))
           (dbg-state [_] (root-dbg)))])))

;; ── Worker Pool ──────────────────────────────────────────────────

(def ^:private ^:const SPIN-YIELD-LIMIT 1000000)

(defn ->worker-pool
  "Create a reusable worker pool with T threads."
  [T]
  (let [cmd-queues    (vec (repeatedly T #(SynchronousQueue.)))
        result-queue  (LinkedBlockingQueue.)
        shutting-down (volatile! false)
        worker-fn     (fn [thread-id]
                        (fn []
                          (loop []
                            (let [cmd (.take ^SynchronousQueue (nth cmd-queues thread-id))]
                              (when-not (= cmd :shutdown)
                                (when-let [^AtomicInteger bc (:barrier-counter cmd)]
                                  (when-not @shutting-down
                                    (.decrementAndGet bc)
                                    (loop [n 0]
                                      (when-not (or (zero? (.get bc)) @shutting-down)
                                        (when (zero? (mod n SPIN-YIELD-LIMIT))
                                          (Thread/yield))
                                        (recur (inc n))))))
                                (let [t0  (System/nanoTime)
                                      res (try
                                            [:ok ((:exec cmd)) (- (System/nanoTime) t0)]
                                            (catch Throwable e
                                              [:ex e (- (System/nanoTime) t0)]))]
                                  (.put result-queue
                                        {:thread-id thread-id
                                         :process-name (:process-name cmd)
                                         :op (:op cmd)
                                         :round (:round cmd)
                                         :result res}))
                                (recur))))))
        workers       (mapv (fn [i]
                              (doto (Thread. ^Runnable (worker-fn i) (str "conc-worker-" i))
                                (.setDaemon true)
                                (.start)))
                            (range T))]
    {:cmd-queues cmd-queues :result-queue result-queue
     :workers workers :shutting-down shutting-down :threads T}))

(defn shutdown-pool!
  "Shut down a worker pool. Sends :shutdown to each worker, joins threads."
  [pool]
  (let [{:keys [cmd-queues workers threads]} pool]
    (doseq [i (range threads)]
      (try (.offer ^SynchronousQueue (nth cmd-queues i) :shutdown 1000 TimeUnit/MILLISECONDS)
           (catch Throwable _)))
    (doseq [^Thread w workers]
      (.join w 2000)
      (when (.isAlive w) (.interrupt w)))))

;; ── S4: Arbiter ───────────────────────────────────────────────────

(defn- weighted-select
  "Select one entry from ops pool by weighted random."
  [^java.util.Random rng ops]
  ;; (prn ops (mapv :weight ops))
  (let [total (reduce + 0 (map :weight ops))
        r     (.nextInt rng (int total))]
    (loop [remaining (int r)
           ops ops]
      (let [op  (first ops)
            remaining (- remaining (int (:weight op)))]
        (if (neg? remaining)
          op
          (recur remaining (rest ops)))))))

(defn- collect-ops
  "Collect all valid ops from non-borrowed processes, annotated with process name."
  [named-processes borrowed-names]
  (into []
        (mapcat (fn [{:keys [name process]}]
                  (when-not (contains? borrowed-names name)
                    (mapv #(assoc % :process-name name) (valid-ops process)))))
        named-processes))

(defn- ->arbiter-state []
  (let [s (object-array [[] {} 0 nil nil 0])]
    [(fn history       ([] (aget s (int 0))) ([x] (aset s (int 0) x)))
     (fn borrowed      ([] (aget s (int 1))) ([x] (aset s (int 1) x)))
     (fn ops-count     ([] (aget s (int 2))) ([x] (aset s (int 2) x)))
     (fn failure       ([] (aget s (int 3))) ([x] (aset s (int 3) x)))
     (fn barrier-state ([] (aget s (int 4))) ([x] (aset s (int 4) x)))
     (fn round         ([] (aget s (int 5))) ([x] (aset s (int 5) x)))]))

(defn run-arbiter
  "Run the arbiter dispatch loop. Returns {:history [...] :failure nil-or-exception}."
  [named-processes violations config pool]
  (let [{:keys [max-ops timeout-ms barrier-init barrier-gap]
         :or {max-ops 10 timeout-ms 500 barrier-init 0 barrier-gap 5}} config
        {:keys [cmd-queues shutting-down threads]} pool
        ^LinkedBlockingQueue result-queue (:result-queue pool)
        T             threads
        _             (assert (<= T (count named-processes))
                              (str "threads (" T ") must be <= processes (" (count named-processes) ")"))
        seed          (or (:seed config) (.nextLong (java.util.Random.)))
        rng           (java.util.Random. (long seed))
        [history borrowed ops-count failure barrier-state round] (->arbiter-state)
        deadline      (+ (System/currentTimeMillis) timeout-ms)
        timed-out?    (fn [] (> (System/currentTimeMillis) deadline))

        dispatch!
        (fn [thread-id op]
          (-> (borrowed) (assoc thread-id (:process-name op)) (borrowed))
          (.put ^SynchronousQueue (nth cmd-queues thread-id)
                {:exec (:exec op)
                 :barrier-counter (when-let [bs (barrier-state)] (:counter bs))
                 :process-name (:process-name op) :op (:op op)
                 :round (round)}))]

    ;; Reset pool state for this run
    (vreset! shutting-down false)
    ;; Drain stale messages from previous runs
    (while (.poll result-queue))

    (try
      (let [all-terminal? (fn [] (every? #(terminal? (:process %)) named-processes))

            dispatch-round!
            (fn []
              (round (inc (round)))
              ;; Barrier creation
              (when (and (nil? (barrier-state))
                         (<= (+ (ops-count) T) max-ops)
                         (or (= (ops-count) barrier-init)
                             (and (> (ops-count) barrier-init)
                                  (zero? (mod (- (ops-count) barrier-init) barrier-gap)))))
                (barrier-state {:counter (AtomicInteger. (int T))}))
              ;; Dispatch to free threads, count dispatched
              (let [dispatched (loop [i 0 k 0]
                                 (if (< i T)
                                   (recur (inc i)
                                          (if (contains? (borrowed) i)
                                            k
                                            (let [pool (collect-ops named-processes (set (vals (borrowed))))]
                                              (if (seq pool)
                                                (do (dispatch! i (weighted-select rng pool)) (inc k))
                                                k))))
                                   k))]
                ;; Adjust barrier for actual participant count, then clear (single-use)
                (when-let [bs (barrier-state)]
                  (when (pos? dispatched)
                    (.addAndGet ^AtomicInteger (:counter bs) (- dispatched T)))
                  (barrier-state nil))))

            record-result!
            (fn [msg]
              (let [tid (:thread-id msg)
                    pn  (:process-name msg)
                    op  (:op msg)
                    res (:result msg)]
                (-> (borrowed) (dissoc tid) (borrowed))
                (-> (history) (conj {:process-name pn :op op
                                     :result res :thread-id tid
                                     :round (:round msg)}) (history))
                (when (seq @violations)
                  (failure (first @violations)))
                (when (and (not (failure))
                           (= :ex (first res))
                           (instance? ProtocolViolation (second res)))
                  (failure (second res)))
                (-> (ops-count) inc (ops-count))))

            collect-round!
            (fn [dl]
              (loop []
                (when (and (seq (borrowed)) (not (failure)))
                  (let [ms-left (- dl (System/currentTimeMillis))
                        msg     (.poll result-queue (max 1 ms-left) TimeUnit/MILLISECONDS)]
                    (when msg
                      (record-result! msg)
                      (recur))))))

            dispatch-loop!
            (fn [dl stop?]
              (let [expired? (fn [] (> (System/currentTimeMillis) dl))]
                (loop []
                  (when-not (or (failure) (expired?))
                    (dispatch-round!)
                    (when (seq (borrowed))
                      (collect-round! dl)
                      (when-not (or (failure) (expired?) (stop?))
                        (recur)))))))]

        ;; Main loop
        (dispatch-loop! deadline
                        #(or (>= (+ (ops-count) (count (borrowed))) max-ops)
                             (all-terminal?)))

        ;; Cancel root, then cleanup loop
        (when-not (or (failure) (all-terminal?))
          (let [root-proc (:process (find-by #(= :root (:role %)) named-processes))]
            (when-some [cancel-op (find-by #(= :cancel (:op %)) (valid-ops root-proc))]
              (let [res (try [:ok ((:exec cancel-op)) 0] (catch Throwable e [:ex e 0]))]
                (-> (history) (conj {:thread-id "c" :process-name "root" :op :cancel :result res :round (round)}) (history)))))
          (dispatch-loop! (+ (System/currentTimeMillis) 100) all-terminal?))

        ;; Failure if cleanup didn't finish
        (when (and (not (failure)) (not (all-terminal?)))
          (let [msg (str "Cleanup timeout: not all processes reached DONE"
                         (apply str (map (fn [{:keys [name process]}]
                                           (str "\n  " name ": " (pr-str (dbg-state process))
                                                " ops=" (pr-str (mapv :op (valid-ops process)))))
                                         named-processes)))]
            (failure (AssertionError. msg)))))

      (catch Throwable e
        (when-not (failure)
          (failure (or (first @violations) e))))

      (finally
        ;; (vreset! shutting-down true)
        ))

    {:history (history) :failure (failure) :seed seed}))

;; ── History Rendering ────────────────────────────────────────────

(defn- format-entry [{:keys [process-name op result]}]
  (let [base (str process-name ":" (name op))]
    (cond
      (and (= :transfer op) (= :ok (first result)))
      (str base " → " (pr-str (second result)))
      (= :ex (first result))
      (str base " → ex:" (ex-message (second result)))
      :else base)))

(defn render-history
  "Print history with bracket grouping for concurrent rounds.
   Precondition: history from strict-round arbiter (no cross-round overlap)."
  [history]
  (let [{cleanup true rounds false}
        (group-by #(= "c" (:thread-id %)) history)
        round-groups (->> (or rounds [])
                          (group-by :round)
                          (sort-by key))]
    (doseq [[_ entries] round-groups]
      (let [n (count entries)]
        (if (= 1 n)
          (printf "  %s%n" (format-entry (first entries)))
          (doseq [[i entry] (map-indexed vector entries)]
            (printf " %s %s%n"
                    (cond (zero? i) "╭" (= i (dec n)) "╰" :else "│")
                    (format-entry entry))))))
    (doseq [entry cleanup]
      (printf " + %s%n" (format-entry entry)))))

;; ── Coverage Stats ──────────────────────────────────────────────

(defn- concurrent-pairs
  "Extract concurrent pairs from strict-round history.
   Returns seq of #{[process-name op] [process-name op]} sets."
  [history]
  (->> history
       (remove #(= "c" (:thread-id %)))
       (group-by :round)
       vals
       (mapcat (fn [entries]
                 (let [tokens (mapv #(vector (:process-name %) (:op %)) entries)]
                   (for [i (range (count tokens))
                         j (range (inc i) (count tokens))]
                     #{(nth tokens i) (nth tokens j)}))))))

(defn- print-coverage [freq pairs]
  (let [by-process (->> freq (group-by (comp first key)) (sort-by key))]
    (doseq [[pn ops] by-process]
      (printf "    %s[%s]%n" pn
              (str/join " " (map (fn [[[_ op] cnt]] (str (name op) ":" cnt))
                                 (sort-by (comp - val) ops))))))
  (let [sorted-pairs (sort-by (comp - val) pairs)
        shown        (take 10 sorted-pairs)
        rest-count   (max 0 (- (count sorted-pairs) 10))
        n-tokens     (count freq)
        possible     (quot (* n-tokens (dec n-tokens)) 2)
        unseen       (- possible (count pairs))]
    (when (seq shown)
      (printf "    pairs: %s"
              (str/join "  "
                        (map (fn [[pair cnt]]
                               (let [[a b] (sort-by first (vec pair))]
                                 (str (first a) ":" (name (second a))
                                      "∥" (first b) ":" (name (second b))
                                      ":" cnt)))
                             shown)))
      (when (pos? rest-count) (printf "  (+%d more)" rest-count))
      (when (pos? unseen) (printf "  (%d unseen)" unseen))
      (println))))

;; ── S5: Escalation Runner ─────────────────────────────────────────

(defn run-conc-test
  "Run escalating concurrency test. setup-fn: (fn [on-violation] -> named-processes)."
  [setup-fn config]
  (let [{:keys [total-ops-budget max-ops timeout-ms threads seed coverage]
         :or {total-ops-budget 1000 max-ops 50 timeout-ms 500 threads 2}} config
        t-all (System/nanoTime)
        pool  (->worker-pool threads)]
    (try
      (loop [ops (long 2)
             total-runs (long 0)
             total-ops  (long 0)]
        (if (> ops max-ops)
          (printf "  %d runs, %d total ops (%.1fs) "
                  total-runs total-ops (/ (- (System/nanoTime) t-all) 1e9))
          (let [runs          (max 1 (quot total-ops-budget ops))
                barrier-init  (min 10 (max 0 (- (quot ops 3) 1)))
                barrier-gap   (max 2 (int (Math/floor (* 2 (Math/log ops)))))]
            (let [[freq pairs]
                  (loop [run 0 freq {} pairs {}]
                    (if (< run runs)
                      (let [violations    (atom [])
                            on-violation  (fn [e] (swap! violations conj e))
                            processes     (setup-fn on-violation)
                            result        (run-arbiter processes violations
                                                       (cond-> {:max-ops ops
                                                                :timeout-ms timeout-ms
                                                                :barrier-init barrier-init
                                                                :barrier-gap barrier-gap}
                                                         seed (assoc :seed seed))
                                                       pool)
                            h             (:history result)]
                        (when-let [f (:failure result)]
                          (println)
                          (printf "FAILURE at ops=%d run=%d threads=%d seed=%d%n"
                                  ops run threads (:seed result))
                          (println "History:")
                          (render-history h)
                          (throw (ex-info (if (instance? Throwable f) (ex-message f) (str f))
                                          {:max-ops ops :seed (:seed result)}
                                          (when (instance? Throwable f) f))))
                        (recur (inc run)
                               (if coverage
                                 (reduce (fn [m e] (update m [(:process-name e) (:op e)] (fnil inc 0))) freq h)
                                 freq)
                               (if coverage
                                 (reduce (fn [m p] (update m p (fnil inc 0))) pairs (concurrent-pairs h))
                                 pairs)))
                      [freq pairs]))]
              (when (and coverage (seq freq))
                (printf "%n    ops=%d (%d runs)%n" ops runs)
                (print-coverage freq pairs)))
            (recur (long (inc ops)) (long (+ total-runs runs)) (long (+ total-ops (* runs ops)))))))
      (finally (shutdown-pool! pool)))))
