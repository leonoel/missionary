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

;; ── Operator factories ──────────────────────────────────────────

(defn- latest-op [w] (apply m/latest (combinator (count w)) w))
(defn- zip-op [w] (apply m/zip (combinator (count w)) w))
(defn- sample-op [w] (apply m/sample (combinator (count w)) w))
(defn- reductions-op [w] (m/reductions conj (first w)))
(defn- eduction-op [w] (m/eduction (map identity) (first w)))
(defn- relieve-op [w] (m/relieve (first w)))
(defn- buffer-op [w] (m/buffer 4 (first w)))

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

(def all-tests
  [["latest/0" latest-0]   ["latest/1" latest-1]   ["latest/2" latest-2]   ["latest/3" latest-3]   ["latest/4" latest-4]
   ["zip/1" zip-1]         ["zip/2" zip-2]         ["zip/3" zip-3]         ["zip/4" zip-4]
   ["sample/2" sample-2]   ["sample/3" sample-3]   ["sample/4" sample-4]
   ["reductions/1" reductions-1]
   ["eduction/1" eduction-1]
   ["relieve/1" relieve-1]
   ["buffer/1" buffer-1]])

(defn- root-cause
  "Walk the cause chain to find the first with a non-nil message."
  [ex]
  (loop [ex ex]
    (if (ex-message ex)
      ex
      (if-let [cause (ex-cause ex)]
        (recur cause)
        ex))))

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
             (println (root-cause e))
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
  (run-tests [["latest/1" latest-1] ["zip/2" zip-2]])
  (run-tests [["bad-setup" bad-setup]])
  (run-tests [["bad-double-step" bad-double-step]])
  (lc/run-lincheck-stress-test bad-double-step {})
  (lc/run-lincheck-stress-test latest-1 {})
  )
