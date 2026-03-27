(ns missionary.conc-incseq-flow-test
  (:require [clojure.string :as str]
            [missionary.conc :as conc]
            [missionary.flow-protocol-enforcer2 :as enforcer2]
            ;; required :electric deps alias
            [hyperfiddle.incseq :as i]))

;; ── Value functions ──────────────────────────────────────────────

(defn- diff-value
  "Stateful diff map from transfer index. Cycles through grow, grow+permute, shrink.
   Sequential consistency: degree(tc) = size_after(tc-1) + grow(tc)."
  [tc]
  (let [k (quot tc 3)
        m (mod tc 3)]
    (case m
      0 {:grow 1 :degree (+ k 1) :shrink 0
         :change {k tc} :permutation {} :freeze #{}}
      1 {:grow 1 :degree (+ k 2) :shrink 0
         :change {(+ k 1) tc} :permutation {k (+ k 1), (+ k 1) k} :freeze #{}}
      2 {:grow 0 :degree (+ k 2) :shrink 1
         :change {} :permutation {k (+ k 1), (+ k 1) k} :freeze #{}})))

(defn- coll-value
  "Collection from transfer index for diff-by. Cycles through [0] [1 2] [0 1 3]."
  [tc]
  (nth [[0] [1 2] [0 1 3]] (mod tc 3)))

(defn- incseq-diff-check
  "Validates that a transferred value is a stateful diff map."
  [v]
  (assert (map? v) (str "expected diff map, got: " (type v)))
  (assert (contains? v :grow) (str "diff map missing :grow key")))

;; ── Helpers (adapted from conc-flow-test) ────────────────────────

(defn- ds
  ([n] (ds n {}))
  ([n opts] (vec (repeatedly n #(conc/->dummy-flow opts)))))

(defn- wrap
  ([on-violation op dummies] (wrap on-violation op dummies {}))
  ([on-violation op dummies opts]
   (mapv #(enforcer2/flow on-violation (str op "-in-" %2) %1 opts) dummies (range))))

(defn- arbiter-processes [out-flow dummies]
  (let [root (conc/->root out-flow)]
    (into [{:name "root" :role :root :process root}]
      (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) dummies)))
  #_(let [[rt rc] (conc/->root out-flow)]
    (into [{:name "root-transfer" :role :root-transfer :process rt}
           {:name "root-cancel" :role :root-cancel :process rc}]
          (map-indexed (fn [i d] {:name (str "df" i) :role :dummy :process d}) dummies))))

(defn- make
  ([on-violation op op-fn dummies] (make on-violation op op-fn dummies {}))
  ([on-violation op op-fn dummies out-opts]
   (let [w   (wrap on-violation op dummies)
         out (enforcer2/flow on-violation (str op "-out") (op-fn w)
                             (merge {:transfer-check incseq-diff-check} out-opts))]
     (arbiter-processes out dummies))))

(defn- combinator [n] (if (= 1 n) identity vector))

;; ── Operator factories ──────────────────────────────────────────

(defn- fixed-op [w] (apply i/fixed w))
(defn- latest-product-op [w] (apply i/latest-product (combinator (count w)) w))
(defn- items-op [w] (i/items (first w)))
(defn- diff-by-op [w] (i/diff-by identity (first w)))

;; ── Setup functions ─────────────────────────────────────────────

;; fixed: children are continuous flows (default DummyFlow producing integers)
(defn fixed-1-setup [v] (let [d (ds 1)] (make v "fixed" fixed-op d)))
(defn fixed-2-setup [v] (let [d (ds 2)] (make v "fixed" fixed-op d)))
(defn fixed-3-setup [v] (let [d (ds 3)] (make v "fixed" fixed-op d)))
(defn fixed-4-setup [v] (let [d (ds 4)] (make v "fixed" fixed-op d)))

;; latest-product: children produce stateful diffs
(def ^:private diff-opts {:value-fn diff-value})
(defn latest-product-1-setup [v] (let [d (ds 1 diff-opts)] (make v "latest-product" latest-product-op d)))
(defn latest-product-2-setup [v] (let [d (ds 2 diff-opts)] (make v "latest-product" latest-product-op d)))
(defn latest-product-3-setup [v] (let [d (ds 3 diff-opts)] (make v "latest-product" latest-product-op d)))
(defn latest-product-4-setup [v] (let [d (ds 4 diff-opts)] (make v "latest-product" latest-product-op d)))

;; items: single child producing stateful diffs
(defn items-1-setup [v] (let [d (ds 1 diff-opts)] (make v "items" items-op d)))

;; diff-by: single child producing collections
(defn diff-by-1-setup [v] (let [d (ds 1 {:value-fn coll-value})] (make v "diff-by" diff-by-op d)))

;; ── Test registry ───────────────────────────────────────────────

(def fixed-tests
  [["fixed/1" fixed-1-setup]
   ["fixed/2" fixed-2-setup]
   ["fixed/3" fixed-3-setup]
   ["fixed/4" fixed-4-setup]])

(def latest-product-tests
  [["latest-product/1" latest-product-1-setup]
   ["latest-product/2" latest-product-2-setup]
   ["latest-product/3" latest-product-3-setup]
   ["latest-product/4" latest-product-4-setup]])

(def items-tests
  [["items/1" items-1-setup]])

(def diff-by-tests
  [["diff-by/1" diff-by-1-setup]])

;; items deferred: cleanup lifecycle issue under concurrent cancel+done.
;; items' cancel sets stepped=true, preventing input-done from calling
;; cleanup-then-done. Root never reaches DONE in the arbiter's drain loop.

(def all-tests
  (into [] cat [fixed-tests latest-product-tests diff-by-tests]))

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
           (when-let [d (ex-data e)]
             (printf "Repro: (run-tests [[\"%s\" %s-setup]] {:max-ops %d :seed %d})%n"
                     nm (str/replace nm "/" "-") (:max-ops d) (:seed d)))
           (throw e)))
       (flush)))))

(def run-all run-tests)

(comment
  (run-tests)
  (run-tests all-tests {:total-ops-budget 100000})
  (run-tests fixed-tests)
  (run-tests latest-product-tests)
  (run-tests items-tests)
  (run-tests diff-by-tests)
  (run-tests [["items/1" items-1-setup]])
  (run-tests [["fixed/2" fixed-2-setup]]))
