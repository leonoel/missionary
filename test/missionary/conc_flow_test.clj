(ns missionary.conc-flow-test
  (:require [missionary.conc :as conc]
            [missionary.core :as m]
            [missionary.flow-protocol-enforcer2 :as enforcer2]))

;; ── Helpers ──────────────────────────────────────────────────────

(defn- ds [n] (vec (repeatedly n conc/->dummy-flow)))

(defn- wrap
  "Wrap each DummyFlow with enforcer2."
  ([on-violation op dummies] (wrap on-violation op dummies {}))
  ([on-violation op dummies opts]
   (mapv #(enforcer2/flow on-violation (str op "-in-" %2) %1 opts) dummies (range))))

(defn- make
  "Build test processes: wrap inputs, apply operator, wrap output, create root.
   out-opts: enforcer2 opts for the output flow (e.g. {:ready-on-init false})."
  ([on-violation op op-fn dummies] (make on-violation op op-fn dummies {}))
  ([on-violation op op-fn dummies out-opts]
   (let [w (wrap on-violation op dummies)
         out (enforcer2/flow on-violation (str op "-out") (op-fn w) out-opts)
         [rt rc] (conc/->root out)]
     (into [{:name "root-transfer" :role :root-transfer :process rt}
            {:name "root-cancel" :role :root-cancel :process rc}]
           (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) dummies)))))

(defn- combinator [n] (if (= 1 n) identity vector))

(defn- enforced-arms
  [on-violation nm pub n]
  (mapv #(enforcer2/flow on-violation (str nm "-arm-" %) pub) (range n)))

;; ── Operator factories ──────────────────────────────────────────

(defn- latest-op [w] (apply m/latest (combinator (count w)) w))
(defn- zip-op [w] (apply m/zip (combinator (count w)) w))
(defn- sample-op [w] (apply m/sample (combinator (count w)) w))
(defn- reductions-op [w] (m/reductions conj (first w)))
(defn- eduction-op [w] (m/eduction (map identity) (first w)))
(defn- relieve-op [w] (m/relieve (first w)))
(defn- buffer-op [w] (m/buffer 4 (first w)))
(defn- eduction-filter-op [w] (m/eduction (filter pos?) (first w)))
(defn- eduction-mapcat-op [w] (m/eduction (mapcat #(vector % %)) (first w)))
(defn- eduction-take-op [w] (m/eduction (take 3) (first w)))
(defn- buffer-small-op [w] (m/buffer 1 (first w)))

;; ── Test definitions ────────────────────────────────────────────

;; Operator tests
(defn latest-0-setup [v] (make v "latest" latest-op []))
(defn latest-1-setup [v] (let [d (ds 1)] (make v "latest" latest-op d)))
(defn latest-2-setup [v] (let [d (ds 2)] (make v "latest" latest-op d)))
(defn latest-3-setup [v] (let [d (ds 3)] (make v "latest" latest-op d)))
(defn latest-4-setup [v] (let [d (ds 4)] (make v "latest" latest-op d)))

(defn zip-1-setup [v] (let [d (ds 1)] (make v "zip" zip-op d)))
(defn zip-2-setup [v] (let [d (ds 2)] (make v "zip" zip-op d)))
(defn zip-3-setup [v] (let [d (ds 3)] (make v "zip" zip-op d)))
(defn zip-4-setup [v] (let [d (ds 4)] (make v "zip" zip-op d)))

(defn sample-2-setup [v] (let [d (ds 2)] (make v "sample" sample-op d)))
(defn sample-3-setup [v] (let [d (ds 3)] (make v "sample" sample-op d)))
(defn sample-4-setup [v] (let [d (ds 4)] (make v "sample" sample-op d)))

(defn reductions-1-setup [v] (let [d (ds 1)] (make v "reductions" reductions-op d)))
(defn eduction-1-setup [v] (let [d (ds 1)] (make v "eduction" eduction-op d)))
(defn relieve-1-setup [v] (let [d (ds 1)] (make v "relieve" relieve-op d)))
(defn buffer-1-setup [v] (let [d (ds 1)] (make v "buffer" buffer-op d)))

(def ^:private discrete {:ready-on-init false})
(defn eduction-filter-setup [v] (let [d (ds 1)] (make v "eduction-filter" eduction-filter-op d discrete)))
(defn eduction-mapcat-setup [v] (let [d (ds 1)] (make v "eduction-mapcat" eduction-mapcat-op d)))
(defn eduction-take-setup [v] (let [d (ds 1)] (make v "eduction-take" eduction-take-op d discrete)))
(defn buffer-small-setup [v] (let [d (ds 1)] (make v "buffer-small" buffer-small-op d)))

;; Signal topologies
(defn signal-basic-setup [v]
  (let [d (ds 1)
        e (fn [nm flow] (enforcer2/flow v nm flow))
        s (m/signal (e "signal-basic-in-0" (first d)))
        [rt rc] (conc/->root (e "signal-basic-out" s))]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

(defn signal-diamond-setup [v]
  (let [d (ds 1)
        e (fn [nm flow] (enforcer2/flow v nm flow))
        s (m/signal (e "signal-diamond-in-0" (first d)))
        [a0 a1] (enforced-arms v "signal-diamond" s 2)
        out (m/signal (m/latest + a0 a1))
        [rt rc] (conc/->root (e "signal-diamond-out" out))]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

(defn signal-triple-setup [v]
  (let [d (ds 1)
        e (fn [nm flow] (enforcer2/flow v nm flow))
        s (m/signal (e "signal-triple-in-0" (first d)))
        [a0 a1 a2] (enforced-arms v "signal-triple" s 3)
        out (m/signal (m/latest + a0 a1 a2))
        [rt rc] (conc/->root (e "signal-triple-out" out))]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

(defn signal-self-sample-setup [v]
  (let [d (ds 1)
        e (fn [nm flow] (enforcer2/flow v nm flow))
        s (m/signal (e "signal-self-sample-in-0" (first d)))
        [a0 a1] (enforced-arms v "signal-self-sample" s 2)
        out (m/signal (m/sample + a0 a1))
        [rt rc] (conc/->root (e "signal-self-sample-out" out))]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

(defn signal-nested-setup [v]
  (let [d (ds 1)
        e (fn [nm flow] (enforcer2/flow v nm flow))
        s1 (m/signal (e "signal-nested-in-0" (first d)))
        [a0 a1] (enforced-arms v "signal-nested-1" s1 2)
        s2 (m/signal (m/latest + a0 a1))
        [b0 b1] (enforced-arms v "signal-nested-2" s2 2)
        out (m/signal (m/latest + b0 b1))
        [rt rc] (conc/->root (e "signal-nested-out" out))]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

(defn signal-mixed-setup [v]
  (let [d (ds 2)
        e (fn [nm flow] (enforcer2/flow v nm flow))
        s0 (m/signal (e "signal-mixed-in-0" (first d)))
        a0 (e "signal-mixed-arm-0" s0)
        a1 (e "signal-mixed-in-1" (second d))
        a2 (e "signal-mixed-arm-2" s0)
        out (m/signal (m/latest vector a0 a1 a2))
        [rt rc] (conc/->root (e "signal-mixed-out" out))]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

(defn signal-semigroup-setup [v]
  (let [d (ds 1)
        e (fn [nm flow] (enforcer2/flow v nm flow))
        s (m/signal conj (e "signal-semigroup-in-0" (first d)))
        [a0 a1] (enforced-arms v "signal-semigroup" s 2)
        out (m/signal (m/latest vector a0 a1))
        [rt rc] (conc/->root (e "signal-semigroup-out" out))]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

;; Stream topologies
(defn stream-basic-setup [v]
  (let [d (ds 1)
        e (fn [nm flow] (enforcer2/flow v nm flow))
        s (m/stream (e "stream-basic-in-0" (first d)))
        [rt rc] (conc/->root (e "stream-basic-out" s))]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

(defn stream-diamond-setup [v]
  (let [d (ds 1)
        e (fn [nm flow] (enforcer2/flow v nm flow))
        s (m/stream (e "stream-diamond-in-0" (first d)))
        [a0 a1] (enforced-arms v "stream-diamond" s 2)
        out (m/stream (m/zip vector a0 a1))
        [rt rc] (conc/->root (e "stream-diamond-out" out))]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

;; Chain topologies
(defn chain-reductions-relieve-setup [v]
  (let [d (ds 1)
        e (fn [nm flow] (enforcer2/flow v nm flow))
        e0  (e "chain-rr-in-0" (first d))
        mid (e "chain-rr-mid" (m/reductions conj e0))
        out (m/relieve mid)
        [rt rc] (conc/->root (e "chain-rr-out" out))]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

(defn chain-filter-reductions-setup [v]
  (let [d (ds 1)
        e (fn [nm flow] (enforcer2/flow v nm flow))
        e0  (e "chain-fr-in-0" (first d))
        mid (enforcer2/flow v "chain-fr-mid" (m/eduction (filter pos?) e0) discrete)
        out (m/reductions conj mid)
        [rt rc] (conc/->root (enforcer2/flow v "chain-fr-out" out discrete))]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

(defn chain-latest-reductions-setup [v]
  (let [d (ds 2)
        e (fn [nm flow] (enforcer2/flow v nm flow))
        w   (wrap v "chain-lr" d)
        mid (e "chain-lr-mid" (apply m/latest vector w))
        out (m/reductions conj mid)
        [rt rc] (conc/->root (e "chain-lr-out" out))]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

;; ── Test registry ───────────────────────────────────────────────

(def operator-tests
  [["latest/0" latest-0-setup]
   ["latest/1" latest-1-setup]
   ["latest/2" latest-2-setup]
   ["latest/3" latest-3-setup]
   ["latest/4" latest-4-setup]
   ["zip/1" zip-1-setup]
   ["zip/2" zip-2-setup]
   ["zip/3" zip-3-setup]
   ["zip/4" zip-4-setup]
   ["sample/2" sample-2-setup]
   ["sample/3" sample-3-setup]
   ["sample/4" sample-4-setup]
   ["reductions/1" reductions-1-setup]
   ["eduction/1" eduction-1-setup]
   ["relieve/1" relieve-1-setup]
   ["buffer/1" buffer-1-setup]
   ["eduction-filter" eduction-filter-setup]
   ["eduction-mapcat" eduction-mapcat-setup]
   ["eduction-take" eduction-take-setup]
   ["buffer-small" buffer-small-setup]])

(def signal-tests
  [["signal-basic" signal-basic-setup]
   ["signal-diamond" signal-diamond-setup]
   ["signal-triple" signal-triple-setup]
   ["signal-self-sample" signal-self-sample-setup]
   ["signal-nested" signal-nested-setup]
   ["signal-mixed" signal-mixed-setup]
   ["signal-semigroup" signal-semigroup-setup]])

(def stream-tests
  [["stream-basic" stream-basic-setup]
   ["stream-diamond" stream-diamond-setup]])

(def chain-tests
  [["chain-reductions-relieve" chain-reductions-relieve-setup]
   ["chain-filter-reductions" chain-filter-reductions-setup]
   ["chain-latest-reductions" chain-latest-reductions-setup]])

(def all-tests
  (into [] cat [operator-tests signal-tests stream-tests chain-tests]))

;; ── Runner ──────────────────────────────────────────────────────

(defn run-tests
  ([] (run-tests all-tests))
  ([tests] (run-tests tests {}))
  ([tests opts]
   (doseq [[nm setup-fn] tests]
     (printf "  %-25s" nm)
     (flush)
     (let [t0 (System/nanoTime)]
       (try
         (conc/run-conc-test setup-fn opts)
         (printf "PASS (%.1fs)%n" (/ (- (System/nanoTime) t0) 1e9))
         (catch Throwable e
           (printf "FAIL (%.1fs)%n" (/ (- (System/nanoTime) t0) 1e9))
           (println (ex-message e))
           (throw e)))
       (flush)))))

(def run-all run-tests)

;; ── Buggy operator: validates framework catches concurrency bugs ─

(defn- buggy-flow-2
  "2-input operator with a check-then-act race on step.
   Correct single-threaded, double-steps under concurrency.
   Eagerly derefs children on step — no pending state to manage."
  [f0 f1]
  (fn [step done]
    (let [stepped    (object-array 1) ;; plain array — no memory barrier. THE BUG.
          crashed    (object-array 1) ;; set on child deref crash or all children done
          done-count (java.util.concurrent.atomic.AtomicInteger. 0)
          its        (object-array 2)
          child-step (fn [i]
                       (if (aget crashed 0)
                         (try @(aget its i)
                              (catch Throwable _)
                              (finally (when (and (= 2 (.get done-count)) (nil? (aget stepped 0))) (done))))
                         (let [ok (try @(aget its i) true ;; eager deref — clears STEPPED
                                       (catch Throwable _
                                         (aset crashed 0 true)
                                         ((aget its 0))
                                         ((aget its 1))
                                         false))]
                           ;; BUG: non-atomic check-then-act
                           (when (and ok (not (aget stepped 0)) (not (aget crashed 0)))
                             (aset stepped 0 true)
                             (step)))))
          child-done (fn []
                       (when (= 2 (.incrementAndGet done-count))
                         (aset crashed 0 true)
                         (when-not (aget stepped 0) (done))))]
      (aset its 0 (f0 #(child-step 0) child-done))
      (aset its 1 (f1 #(child-step 1) child-done))
      (reify
        clojure.lang.IFn
        (invoke [_]
          ((aget its 0))
          ((aget its 1)))
        clojure.lang.IDeref
        (deref [_]
          (aset stepped 0 nil)
          (when (= 2 (.get done-count)) (done))
          nil)))))

(defn buggy-flow-2-setup [v]
  (let [d0  (conc/->dummy-flow {:step-on-init false})
        d1  (conc/->dummy-flow {:step-on-init false})
        d   [d0 d1]
        w   (wrap v "buggy" d discrete)
        out (enforcer2/flow v "buggy-out" (buggy-flow-2 (first w) (second w)) discrete)
        [rt rc] (conc/->root out)]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) d))))

(def validation-tests
  [["buggy-flow/2" buggy-flow-2-setup]])

(comment
  (run-tests all-tests {:total-ops-budget 1000000})
  (run-tests operator-tests)
  (run-tests signal-tests)
  (run-tests stream-tests)
  (run-tests chain-tests)
  (run-tests validation-tests)
  (run-tests [["latest/2" latest-2-setup]])
  (run-tests all-tests {:total-ops-budget 100 :max-ops 10}))
