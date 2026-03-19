(ns missionary.lincheck-flow-test
  (:require [clojure.string :as str]
            [missionary.core :as m]
            [missionary.lincheck :as lc]
            [missionary.flow-protocol-enforcer :as enforcer]))

;; ── Helpers ──────────────────────────────────────────────────────

(defn- wrap [op dummies]
  (mapv #(enforcer/flow (str op "-in-" %2) %1) dummies (range)))

(defn- make [op op-fn dummies]
  (let [w (wrap op dummies)]
    (into [(lc/->root (enforcer/flow (str op "-out") (op-fn w)))] dummies)))

(defn- combinator [n] (if (= 1 n) identity vector))

(defn- ds [n] (vec (repeatedly n lc/->dummy-flow)))

(defn- enforced-arms
  [name pub n]
  (mapv #(enforcer/flow (str name "-arm-" %) pub) (range n)))

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

(lc/def-lincheck-flow-test latest-0 0 (make "latest" latest-op []))
(lc/def-lincheck-flow-test latest-1 1 (let [d (ds 1)] (make "latest" latest-op d)))
(lc/def-lincheck-flow-test latest-2 2 (let [d (ds 2)] (make "latest" latest-op d)))
(lc/def-lincheck-flow-test latest-3 3 (let [d (ds 3)] (make "latest" latest-op d)))
(lc/def-lincheck-flow-test latest-4 4 (let [d (ds 4)] (make "latest" latest-op d)))

(lc/def-lincheck-flow-test zip-1 1 (let [d (ds 1)] (make "zip" zip-op d)))
(lc/def-lincheck-flow-test zip-2 2 (let [d (ds 2)] (make "zip" zip-op d)))
(lc/def-lincheck-flow-test zip-3 3 (let [d (ds 3)] (make "zip" zip-op d)))
(lc/def-lincheck-flow-test zip-4 4 (let [d (ds 4)] (make "zip" zip-op d)))

(lc/def-lincheck-flow-test sample-2 2 (let [d (ds 2)] (make "sample" sample-op d)))
(lc/def-lincheck-flow-test sample-3 3 (let [d (ds 3)] (make "sample" sample-op d)))
(lc/def-lincheck-flow-test sample-4 4 (let [d (ds 4)] (make "sample" sample-op d)))

(lc/def-lincheck-flow-test reductions-1 1 (let [d (ds 1)] (make "reductions" reductions-op d)))
(lc/def-lincheck-flow-test eduction-1  1 (let [d (ds 1)] (make "eduction" eduction-op d)))
(lc/def-lincheck-flow-test relieve-1   1 (let [d (ds 1)] (make "relieve" relieve-op d)))
(lc/def-lincheck-flow-test buffer-1    1 (let [d (ds 1)] (make "buffer" buffer-op d)))

;; ── Basic: additional operator variants ───────────────────────

(lc/def-lincheck-flow-test eduction-filter 1 (let [d (ds 1)] (make "eduction-filter" eduction-filter-op d)))
(lc/def-lincheck-flow-test eduction-mapcat 1 (let [d (ds 1)] (make "eduction-mapcat" eduction-mapcat-op d)))
(lc/def-lincheck-flow-test eduction-take   1 (let [d (ds 1)] (make "eduction-take" eduction-take-op d)))
(lc/def-lincheck-flow-test buffer-small    1 (let [d (ds 1)] (make "buffer-small" buffer-small-op d)))

;; ── Signal topologies ─────────────────────────────────────────

(lc/def-lincheck-flow-test signal-basic 1
  (let [d (ds 1)
        s (m/signal (enforcer/flow "signal-basic-in-0" (first d)))]
    (into [(lc/->root (enforcer/flow "signal-basic-out" s))] d)))

(lc/def-lincheck-flow-test signal-diamond 1
  (let [d (ds 1)
        s (m/signal (enforcer/flow "signal-diamond-in-0" (first d)))
        [a0 a1] (enforced-arms "signal-diamond" s 2)
        out (m/signal (m/latest + a0 a1))]
    (into [(lc/->root (enforcer/flow "signal-diamond-out" out))] d)))

(lc/def-lincheck-flow-test signal-triple 1
  (let [d (ds 1)
        s (m/signal (enforcer/flow "signal-triple-in-0" (first d)))
        [a0 a1 a2] (enforced-arms "signal-triple" s 3)
        out (m/signal (m/latest + a0 a1 a2))]
    (into [(lc/->root (enforcer/flow "signal-triple-out" out))] d)))

(lc/def-lincheck-flow-test signal-self-sample 1
  (let [d (ds 1)
        s (m/signal (enforcer/flow "signal-self-sample-in-0" (first d)))
        [a0 a1] (enforced-arms "signal-self-sample" s 2)
        out (m/signal (m/sample + a0 a1))]
    (into [(lc/->root (enforcer/flow "signal-self-sample-out" out))] d)))

(lc/def-lincheck-flow-test signal-nested 1
  (let [d (ds 1)
        s1 (m/signal (enforcer/flow "signal-nested-in-0" (first d)))
        [a0 a1] (enforced-arms "signal-nested-1" s1 2)
        s2 (m/signal (m/latest + a0 a1))
        [b0 b1] (enforced-arms "signal-nested-2" s2 2)
        out (m/signal (m/latest + b0 b1))]
    (into [(lc/->root (enforcer/flow "signal-nested-out" out))] d)))

(lc/def-lincheck-flow-test signal-mixed 2
  (let [d (ds 2)
        s0 (m/signal (enforcer/flow "signal-mixed-in-0" (first d)))
        a0 (enforcer/flow "signal-mixed-arm-0" s0)
        a1 (enforcer/flow "signal-mixed-in-1" (second d))
        a2 (enforcer/flow "signal-mixed-arm-2" s0)
        out (m/signal (m/latest vector a0 a1 a2))]
    (into [(lc/->root (enforcer/flow "signal-mixed-out" out))] d)))

(lc/def-lincheck-flow-test signal-semigroup 1
  (let [d (ds 1)
        s (m/signal conj (enforcer/flow "signal-semigroup-in-0" (first d)))
        [a0 a1] (enforced-arms "signal-semigroup" s 2)
        out (m/signal (m/latest vector a0 a1))]
    (into [(lc/->root (enforcer/flow "signal-semigroup-out" out))] d)))

;; ── Stream topologies ─────────────────────────────────────────

(lc/def-lincheck-flow-test stream-basic 1
  (let [d (ds 1)
        s (m/stream (enforcer/flow "stream-basic-in-0" (first d)))]
    (into [(lc/->root (enforcer/flow "stream-basic-out" s))] d)))

(lc/def-lincheck-flow-test stream-diamond 1
  (let [d (ds 1)
        s (m/stream (enforcer/flow "stream-diamond-in-0" (first d)))
        [a0 a1] (enforced-arms "stream-diamond" s 2)
        out (m/stream (m/zip vector a0 a1))]
    (into [(lc/->root (enforcer/flow "stream-diamond-out" out))] d)))

;; ── Chain topologies ──────────────────────────────────────────

(lc/def-lincheck-flow-test chain-reductions-relieve 1
  (let [d (ds 1)
        e0  (enforcer/flow "chain-rr-in-0" (first d))
        mid (enforcer/flow "chain-rr-mid" (m/reductions conj e0))
        out (m/relieve mid)]
    (into [(lc/->root (enforcer/flow "chain-rr-out" out))] d)))

(lc/def-lincheck-flow-test chain-filter-reductions 1
  (let [d (ds 1)
        e0  (enforcer/flow "chain-fr-in-0" (first d))
        mid (enforcer/flow "chain-fr-mid" (m/eduction (filter pos?) e0))
        out (m/reductions conj mid)]
    (into [(lc/->root (enforcer/flow "chain-fr-out" out))] d)))

(lc/def-lincheck-flow-test chain-latest-reductions 2
  (let [d (ds 2)
        w   (wrap "chain-lr" d)
        mid (enforcer/flow "chain-lr-mid" (apply m/latest vector w))
        out (m/reductions conj mid)]
    (into [(lc/->root (enforcer/flow "chain-lr-out" out))] d)))

;; ── Failing tests (for verifying error reporting) ─────────────

(lc/def-lincheck-flow-test bad-setup 0
  (throw (ex-info "intentional setup failure" {})))

(defn- double-step-flow
  "Buggy flow wrapper: calls step twice during transfer."
  [upstream]
  (fn [step done]
    (let [it (upstream step done)]
      (reify
        clojure.lang.IDeref
        (deref [_]
          (let [v @it]
            (step)
            (step)
            v))
        clojure.lang.IFn
        (invoke [_] (it))))))

(lc/def-lincheck-flow-test bad-double-step 1
  (let [d (lc/->dummy-flow)]
    [(lc/->root (enforcer/flow "bad-out"
                  (double-step-flow (enforcer/flow "bad-in" d))))
     d]))

;; ── Runner ──────────────────────────────────────────────────────

(def operator-tests
  [["latest/0" latest-0]   ["latest/1" latest-1]   ["latest/2" latest-2]   ["latest/3" latest-3]   ["latest/4" latest-4]
   ["zip/1" zip-1]         ["zip/2" zip-2]         ["zip/3" zip-3]         ["zip/4" zip-4]
   ["sample/2" sample-2]   ["sample/3" sample-3]   ["sample/4" sample-4]
   ["reductions/1" reductions-1]
   ["eduction/1" eduction-1]
   ["relieve/1" relieve-1]
   ["buffer/1" buffer-1]
   ["eduction-filter" eduction-filter]
   ["eduction-mapcat" eduction-mapcat]
   ["eduction-take" eduction-take]
   ["buffer-small" buffer-small]])

(def signal-tests
  [["signal-basic" signal-basic]
   ["signal-diamond" signal-diamond]
   ["signal-triple" signal-triple]
   ["signal-self-sample" signal-self-sample]
   ["signal-nested" signal-nested]
   ["signal-mixed" signal-mixed]
   ["signal-semigroup" signal-semigroup]])

(def stream-tests
  [["stream-basic" stream-basic]
   ["stream-diamond" stream-diamond]])

(def chain-tests
  [["chain-reductions-relieve" chain-reductions-relieve]
   ["chain-filter-reductions" chain-filter-reductions]
   ["chain-latest-reductions" chain-latest-reductions]])

(def all-tests
  (into [] cat [operator-tests signal-tests stream-tests chain-tests]))

(defn run-tests
  "Run a set of lincheck flow stress tests sequentially.
   tests is a seq of [name class] pairs.
   Continues on failure, prints summary at end."
  ([tests] (run-tests tests {}))
  ([tests opts]
   (let [opts    (merge {:iterations 100 :threads 2} opts)
         t-all   (System/nanoTime)
         failed  (volatile! [])]
     (doseq [[nm cls] tests]
       (printf "  %-20s" nm)
       (flush)
       (let [t0 (System/nanoTime)]
         (try
           (lc/run-lincheck-stress-test cls opts)
           (printf "PASS  (%.1fs)%n" (/ (- (System/nanoTime) t0) 1e9))
           (catch Throwable e
             (printf "FAIL  (%.1fs)%n" (/ (- (System/nanoTime) t0) 1e9))
             (println (ex-message e))
             (vswap! failed conj nm))))
       (flush))
     (let [nfail (count @failed)
           npass (- (count tests) nfail)]
       (if (zero? nfail)
         (printf "All %d tests passed (%.1fs)%n" npass (/ (- (System/nanoTime) t-all) 1e9))
         (printf "%d passed, %d failed (%.1fs): %s%n"
           npass nfail (/ (- (System/nanoTime) t-all) 1e9) (str/join ", " @failed)))
       (flush)))))

(defn run-all
  "Run all lincheck flow stress tests."
  ([] (run-tests all-tests))
  ([opts] (run-tests all-tests opts)))

(comment
  (run-all)
  (run-all {:iterations 50})
  (run-tests operator-tests)
  (run-tests signal-tests)
  (run-tests stream-tests)
  (run-tests chain-tests)
  (run-tests [["latest/1" latest-1] ["zip/2" zip-2]])
  (run-tests [["signal-diamond" signal-diamond]])
  (run-tests [["bad-setup" bad-setup]])
  (run-tests [["bad-double-step" bad-double-step]])
  (lc/run-lincheck-stress-test bad-double-step {})
  (lc/run-lincheck-stress-test signal-diamond {})
  )
