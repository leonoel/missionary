(ns missionary.pairing-heap-test
  (:require [clojure.test :as t]
            [missionary.pairing-heap-test-impl :as phi]
            [clojure.set :as set]))

(defn build-command-plan [n-inserts n-threads]
  (shuffle (concat
             (map (fn [i] {:v i, :op :insert, :thread (rand-int n-threads)}) (range n-inserts))
             (repeatedly (inc (rand-int n-inserts)) (fn [] {:op :accept, :thread (rand-int n-threads)})))))

(defn get-thread-plan [plan thread-idx]
  (filterv #(= thread-idx (:thread %)) plan))

(defn ?accept [h !state]
  (when (compare-and-set! (phi/get-ready-atom h) true false)
    (swap! !state update :batch* conj (phi/accept-as-vec h))))

(defn interpret [h !state insn]
  (case (:op insn)
    :insert (phi/insert-node h (:v insn))
    :accept (?accept h !state)))

(defn spawn-thread [h !state thread-plan]
  (let [thread (new Thread (fn [] (run! (partial interpret h !state) thread-plan)))]
    (.start thread)
    thread))

(defn !value-lost [batch* n-inserts]
  (let [ns (set (range n-inserts))
        vals (set (eduction cat batch*))
        diff (set/difference ns vals)]
    (when (seq diff)
      {:failure :value-lost, :lost-values diff})))

(defn !double-value [batch*]
  (let [coll (into [] cat batch*)]
    (when-not (= (count coll) (count (set coll)))
      (let [freq (frequencies coll)
            doubled (into #{} (keep (fn [[k v]] (when (> v 1) k))) freq)]
        {:failure :double-value, :double-values doubled}))))

(defn !out-of-order [batch*]
  (reduce (fn [_ batch]
            (when-not (= batch (sort batch))
              (reduced {:failure :out-of-order, :batch batch})))
    nil batch*))

(defn run-test* [n-inserts n-threads plan]
  (let [!state (atom {:plan plan, :batch* []})
        h (phi/heap)
        thread* (mapv #(spawn-thread h !state (get-thread-plan plan %)) (range n-threads))]
    (run! #(.join %) thread*)
    (?accept h !state)
    (swap! !state
      (fn [{:keys [batch*] :as state}]
        (let [failures (filterv some? [(!value-lost batch* n-inserts)
                                       (!double-value batch*)
                                       (!out-of-order batch*)])]
          (cond-> state (seq failures) (assoc :failures failures)))))
    @!state))

(defn run-test
  ([] (run-test 10 2))
  ([n-inserts n-threads] (run-test* n-inserts n-threads (build-command-plan n-inserts n-threads))))

(t/deftest concurrency-test
  (t/is (nil? (reduce (fn [_ n]
                        (let [test (run-test (+ (quot n 10) 10) (+ (quot n 100) 2))]
                          (when (:failures test) (reduced test)))) (range 1000)))))
