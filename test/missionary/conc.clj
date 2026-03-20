(ns missionary.conc
  "Concurrency test framework for missionary flows.
   Generates protocol-valid scenarios by construction via process ownership."
  (:import (clojure.lang ExceptionInfo IDeref IFn)
           (java.util.concurrent
            LinkedBlockingQueue SynchronousQueue TimeUnit)
           (java.util.concurrent.atomic AtomicInteger)
           (missionary ProtocolViolation)))

(set! *warn-on-reflection* true)

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

(defn ->dummy-flow
  ([] (->dummy-flow {}))
  ([{:keys [step-on-init weights]
     :or   {step-on-init true
            weights      {:step 40 :done 5 :crash 10 :re-step 10}}}]
   (let [state         (AtomicInteger. 0)
         should-throw  (volatile! false)
         re-step-count (volatile! 0)
         xfer-count    (volatile! 0)
         step-cb       (volatile! nil)
         done-cb       (volatile! nil)
         do-step       (fn []
                         (loop []
                           (let [old (.get state)]
                             (if (pos? (bit-and old (bit-or DF-STEPPED DF-DONE DF-CRASHED)))
                               nil
                               (if (.compareAndSet state old (bit-or old DF-STEPPED))
                                 (do (@step-cb) nil)
                                 (recur))))))
         do-done       (fn []
                         (loop []
                           (let [old (.get state)]
                             (cond
                               (pos? (bit-and old DF-DONE))                    nil
                               (pos? (bit-and old DF-STEPPED))                 nil
                               (.compareAndSet state old (bit-or old DF-DONE)) (do (@done-cb) nil)
                               :else                                           (recur)))))
         do-crash      (fn [] (vreset! should-throw true) nil)
         do-re-step    (fn [] (vswap! re-step-count inc) nil)]
     (reify
       ProcessDebug
       (valid-ops [_]
         (let [s        (.get state)
               stepped? (pos? (bit-and s DF-STEPPED))
               done?    (pos? (bit-and s DF-DONE))
               crashed? (pos? (bit-and s DF-CRASHED))]
           (cond-> []
             (and (not stepped?) (not done?) (not crashed?))
             (conj {:op :step :weight (:step weights) :exec do-step})
             (and (not done?) (not stepped?))
             (conj {:op :done :weight (:done weights) :exec do-done})
             (and (not done?) (not crashed?))
             (conj {:op     :crash
                    :weight (cond-> (:crash weights) stepped? (* 2))
                    :exec   do-crash})
             (and (not done?) (not crashed?))
             (conj {:op :re-step :weight (:re-step weights) :exec do-re-step}))))
       (terminal? [_]
         (pos? (bit-and (.get state) DF-DONE)))
       (dbg-state [_]
         (let [s (.get state)]
           {:raw         s
            :stepped     (pos? (bit-and s DF-STEPPED))
            :done        (pos? (bit-and s DF-DONE))
            :cancelled   (pos? (bit-and s DF-CANCELLED))
            :crashed     (pos? (bit-and s DF-CRASHED))
            :throw-armed @should-throw
            :re-step     @re-step-count}))
       IFn
       (invoke [_ step done]
         (vreset! step-cb step)
         (vreset! done-cb done)
         (when step-on-init
           (loop []
             (let [old (.get state)]
               (when-not (.compareAndSet state old (bit-or old DF-STEPPED))
                 (recur))))
           (@step-cb))
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
             (if @should-throw
               (do (loop []
                     (let [old (.get state)]
                       (when-not (.compareAndSet state old
                                                 (bit-or (bit-and old (bit-not DF-STEPPED)) DF-CRASHED))
                         (recur))))
                   (throw (ex-info "intended crash" {})))
               (let [tc    (vswap! xfer-count inc)
                     value (transfer-value (dec tc))]
                 (loop []
                   (let [old (.get state)]
                     (when-not (.compareAndSet state old (bit-and old (bit-not DF-STEPPED)))
                       (recur))))
                 ;; Re-step: fire step during transfer (consecutive transfer)
                 (when (pos? @re-step-count)
                   (vswap! re-step-count dec)
                   (do-step))
                 value)))))))))

;; ── S3: Root Consumer ─────────────────────────────────────────────

(def ^:private ^:const RT-TRANSFERRED 0)
(def ^:private ^:const RT-STEPPED 1)
(def ^:private ^:const RT-CLAIMED 2)
(def ^:private ^:const RT-SDT 3)        ; SteppedDuringTransfer
(def ^:private ^:const RT-DONE 4)

(defn ->root
  [flow]
  (let [state      (AtomicInteger. RT-TRANSFERRED)
        cancelled  (volatile! false)
        iterator   (volatile! nil)
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
    (vreset! iterator iter)
    (let [transfer-fn
          (fn []
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
                  ret (try @@iterator
                           (catch Throwable e
                             (update-state!)
                             (throw e)))]
              (update-state!)
              ret))
          cancel-fn
          (fn []
            (@iterator)
            (vreset! cancelled true)
            nil)]
      (let [root-dbg (fn [] {:raw (.get state)
                             :state (case (.get state)
                                      0 :transferred 1 :stepped 2 :claimed
                                      3 :sdt 4 :done (.get state))
                             :cancelled @cancelled})]
        [(reify ProcessDebug
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
                 :weight (if @cancelled 50 5)
                 :exec cancel-fn}]))
           (terminal? [_] (= (.get state) RT-DONE))
           (dbg-state [_] (root-dbg)))]))))

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
  "Collect all valid ops from non-owned processes, annotated with process name."
  [named-processes owned-set]
  (into []
        (mapcat (fn [{:keys [name process]}]
                  (when-not (contains? owned-set name)
                    (map #(assoc % :process-name name) (valid-ops process)))))
        named-processes))

(defn- dummy-processes
  "Return only the DummyFlow processes (role :dummy)."
  [named-processes]
  (filterv #(= :dummy (:role %)) named-processes))

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
        history       (volatile! [])
        owned         (volatile! #{})
        ops-count     (volatile! 0)
        failure       (volatile! nil)
        ;; Barrier state: {:counter AtomicInteger :remaining int} or nil
        barrier-state (volatile! nil)
        deadline      (+ (System/currentTimeMillis) timeout-ms)
        timed-out?    (fn [] (> (System/currentTimeMillis) deadline))

        dispatch!
        (fn [thread-id op]
          (vswap! owned conj (:process-name op))
          (let [bc (when-let [bs @barrier-state]
                     (when (pos? (:remaining bs))
                       (vswap! barrier-state update :remaining dec)
                       (:counter bs)))]
            (.put ^SynchronousQueue (nth cmd-queues thread-id)
                  {:exec (:exec op) :barrier-counter bc
                   :process-name (:process-name op) :op (:op op)})))]

    ;; Reset pool state for this run
    (vreset! shutting-down false)
    ;; Drain stale messages from previous runs
    (while (.poll result-queue))

    (try
      ;; Initial dispatch — one op per thread
      (let [pool (collect-ops named-processes @owned)]
        (doseq [i (range (min T (count pool)))]
          (let [op (weighted-select rng
                                    (remove #(contains? @owned (:process-name %)) pool))]
            (dispatch! i op))))

      ;; Main loop
      (let [dummies (dummy-processes named-processes)]
        (loop []
          (when-not (or @failure (timed-out?))
            (let [ms-left (- deadline (System/currentTimeMillis))
                  msg     (.poll result-queue ms-left TimeUnit/MILLISECONDS)]
              (when msg
                (let [tid  (:thread-id msg)
                      pn   (:process-name msg)
                      op   (:op msg)
                      res  (:result msg)]
                  ;; Un-own process
                  (vswap! owned disj pn)
                  ;; Record history
                  (vswap! history conj {:process-name pn :op op
                                        :result res :thread-id tid})
                  ;; Check violations
                  (when (seq @violations)
                    (vreset! failure (first @violations)))
                  ;; Check ProtocolViolation in result
                  (when (and (not @failure)
                             (= :ex (first res))
                             (instance? ProtocolViolation (second res)))
                    (vreset! failure (second res)))
                  ;; Increment and check limits
                  (vswap! ops-count inc)
                  (when-not (or @failure
                                (>= @ops-count max-ops)
                                (every? #(terminal? (:process %)) dummies))
                    ;; Barrier check — fresh AtomicInteger per round
                    (when (and (nil? @barrier-state)
                               (<= (+ @ops-count T) max-ops)
                               (or (= @ops-count barrier-init)
                                   (and (> @ops-count barrier-init)
                                        (zero? (mod (- @ops-count barrier-init) barrier-gap)))))
                      (vreset! barrier-state
                               {:counter (AtomicInteger. (int T)) :remaining T}))
                    ;; Clear exhausted barrier
                    (when (and @barrier-state
                               (zero? (:remaining @barrier-state)))
                      (vreset! barrier-state nil))
                    ;; Select and dispatch
                    (let [pool (collect-ops named-processes @owned)]
                      (when (seq pool)
                        (dispatch! tid (weighted-select rng pool))))
                    (recur))))))))

      ;; Signal shutdown — workers check shutting-down in spin loop and exit
      (vreset! shutting-down true)

      ;; Drain in-flight ops
      (loop []
        (when (seq @owned)
          (let [result (.poll result-queue 100 TimeUnit/MILLISECONDS)]
            (when result
              (vswap! owned disj (:process-name result))
              (vswap! history conj {:process-name (:process-name result)
                                    :op (:op result)
                                    :result (:result result)
                                    :thread-id (:thread-id result)}))
            (recur))))

      ;; Active cleanup — cancel root, done DummyFlows, drain to DONE
      (when-not @failure
        (let [root-cancel (first (filter #(= :root-cancel (:role %)) named-processes))
              root-xfer   (first (filter #(= :root-transfer (:role %)) named-processes))
              dummies     (dummy-processes named-processes)]
          ;; Cancel root if not done
          (when (and root-cancel (seq (valid-ops (:process root-cancel))))
            ((:exec (first (valid-ops (:process root-cancel))))))
          ;; Drain loop
          (loop []
            (when-not (or (terminal? (:process root-xfer)) (timed-out?))
              ;; Transfer if available — crash-armed DummyFlows and cancelled flows throw expected exceptions
              (when-let [ops (seq (valid-ops (:process root-xfer)))]
                (try ((:exec (first ops)))
                     (catch ExceptionInfo e
                       (when-not (= "intended crash" (ex-message e)) (throw e)))
                     (catch missionary.Cancelled _)))
              ;; Done each DummyFlow if available
              (doseq [d dummies]
                (when-let [done-op (first (filter #(= :done (:op %))
                                                  (valid-ops (:process d))))]
                  ((:exec done-op))))
              (Thread/yield)
              (recur)))
          ;; Report failure if root didn't terminate
          (when-not (terminal? (:process root-xfer))
            (if (seq @violations)
              (vreset! failure (first @violations))
              (when (timed-out?)
                (let [msg (str "Cleanup timeout: root did not reach DONE"
                               "\n  root: " (pr-str (dbg-state (:process root-xfer)))
                               " ops=" (pr-str (mapv :op (valid-ops (:process root-xfer))))
                               (apply str (map (fn [d] (str "\n  " (:name d) ": " (pr-str (dbg-state (:process d)))
                                                            " ops=" (pr-str (mapv :op (valid-ops (:process d))))))
                                               dummies)))]
                  (vreset! failure (AssertionError. msg))))))))

      (catch Throwable e
        (when-not @failure
          (vreset! failure (or (first @violations) e))))

      (finally
        (vreset! shutting-down true)))

    {:history @history :failure @failure :seed seed}))

;; ── S5: Escalation Runner ─────────────────────────────────────────

(defn run-conc-test
  "Run escalating concurrency test. setup-fn: (fn [on-violation] -> named-processes)."
  [setup-fn config]
  (let [{:keys [total-ops-budget max-ops timeout-ms threads]
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
            (loop [run 0]
              (when (< run runs)
                (let [violations    (atom [])
                      on-violation  (fn [e] (swap! violations conj e))
                      processes     (setup-fn on-violation)
                      result        (run-arbiter processes violations
                                                 {:max-ops ops
                                                  :timeout-ms timeout-ms
                                                  :barrier-init barrier-init
                                                  :barrier-gap barrier-gap}
                                                 pool)]
                  (when-let [f (:failure result)]
                    (println)
                    (printf "FAILURE at ops=%d run=%d threads=%d seed=%d%n"
                            ops run threads (:seed result))
                    (println "History:")
                    (doseq [entry (:history result)]
                      (printf "  [t%d] %-15s %-10s %s%n"
                              (:thread-id entry) (:process-name entry)
                              (name (:op entry)) (pr-str (:result entry))))
                    (throw (if (instance? Throwable f) f
                               (AssertionError. (str f)))))
                  (recur (inc run)))))
            (recur (long (inc ops)) (long (+ total-runs runs)) (long (+ total-ops (* runs ops)))))))
      (finally (shutdown-pool! pool)))))
