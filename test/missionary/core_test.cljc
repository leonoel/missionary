(ns missionary.core-test
  (:require
    [missionary.core :as m :include-macros true]
    [missionary.tck :refer [if-try deftask defflow] :include-macros true]))

(def =? (partial partial =))

(deftask sleep-success
  {:timeout 10
   :success nil?}
  (m/sleep 0))

(deftask sleep-failure
  {:cancel  0
   :timeout 10
   :failure (comp :cancelled ex-data)}
  (m/sleep 100))

(deftask semaphore
  {:timeout 200
   :success nil?}
  (m/sp
    (let [sem (m/sem 7)]
      (m/? (->> (m/sp (while true (m/holding sem (m/? (m/sleep 0)))))
                (repeat 100)
                (apply m/join vector)
                (m/timeout 100)
                (m/attempt)))
      (dotimes [_ 7] (m/? sem)))))

(deftask rendezvous
  {:cancel 150
   :timeout 200
   :failure (comp :cancelled ex-data)}
  (m/sp
    (let [rdv (m/rdv)]
      (m/? (->> (m/sp (while true
                        (m/? (m/compel (m/join vector rdv (rdv false))))
                        (m/? (m/sleep 0))))
                (repeat 100)
                (apply m/join vector)
                (m/timeout 100)
                (m/attempt)))
      (m/? rdv))))

(deftask mailbox
  {:cancel 150
   :timeout 200
   :failure (comp :cancelled ex-data)}
  (m/sp
    (let [mbx (m/mbx)]
      (m/? (->> (m/sp (while true
                        (mbx false)
                        (m/? (m/compel mbx))
                        (m/? (m/sleep 0))))
                (repeat 100)
                (apply m/join)
                (m/timeout 100)
                (m/attempt)))
      (m/? mbx))))

(deftask dataflow
  {:success (=? true)}
  (let [dfv (m/dfv)] (dfv true) dfv))

(defflow ambiguous
  {:results (map =? [6 19 28 57 87])
   :timeout 250}
  (m/ap
    (let [x (m/?= (m/enumerate [19 57 28 6 87]))]
      (m/? (m/sleep x x)))))

(defflow debounce
  {:results (map =? [24 79 9 37])
   :timeout 500}
  (letfn [(deb [delay flow]
            (m/ap (let [x (m/?! flow)]
                    (if-try [x (m/? (m/sleep delay x))]
                      x (m/?? m/none)))))]
    (->> (m/ap (let [n (m/?? (m/enumerate [24 79 67 34 18 9 99 37]))]
                 (m/? (m/sleep n n))))
         (deb 50))))

(deftask aggregate
  {:success (=? [1 2 3])}
  (m/aggregate conj (m/enumerate [1 2 3])))

(deftask aggregate-reduced
  {:success (=? true)}
  (m/aggregate (fn [_ _] (reduced true)) nil (m/enumerate [1 2 3])))

(deftask aggregate-failure
  {:failure (comp :failed ex-data)}
  (m/aggregate (fn [_ _] (throw (ex-info "this is fine." {:failed true}))) nil (m/ap)))

(deftask aggregate-input-failure
  {:failure (comp :failed ex-data)}
  (m/aggregate conj (m/ap (throw (ex-info "this is fine." {:failed true})))))

(defflow integrate
  {:results (map =? [[] [1] [1 2] [1 2 3]])}
  (m/integrate conj (m/enumerate [1 2 3])))

(defflow integrate-reduced
  {:results (map =? [nil true])}
  (m/integrate (fn [_ _] (reduced true)) nil (m/enumerate [1 2 3])))

(defflow integrate-failure
  {:results [(=? nil)]
   :failure (comp :failed ex-data)}
  (m/integrate (fn [_ _] (throw (ex-info "this is fine." {:failed true}))) nil (m/enumerate [1 2 3])))

(defflow integrate-input-failure
  {:results [(=? [])]
   :failure (comp :failed ex-data)}
  (m/integrate conj (m/ap (throw (ex-info "this is fine." {:failed true})))))

(defflow transform
  {:results (map =? (range 10))}
  (m/transform identity (m/enumerate (range 10))))

(defflow transform-shrinking
  {:results (map =? [[0 0 1 2] [0 1 2 3] [4 0 1 2] [3 4 5 6] [0 1 2 3] [4 5 6 7] [8]])}
  (m/transform (comp (filter odd?) (mapcat range) (partition-all 4)) (m/enumerate (range 10))))

(defflow transform-expanding
  {:results (map =? [0 0 1 2 0 1 2 3 4])}
  (m/transform (comp (filter odd?) (mapcat range) (take 9)) (m/enumerate (range 10))))

(defflow transform-failure
  {:failure (comp :failed ex-data)}
  (m/transform (map (fn [_] (throw (ex-info "this is fine." {:failed true})))) (m/ap)))

(defflow transform-input-failure
  {:failure (comp :failed ex-data)}
  (m/transform identity (m/ap (throw (ex-info "this is fine." {:failed true})))))

(defflow gather
  {:results (map =? [1 :a 2 :b 3 :c])}
  (m/gather (m/enumerate [1 2 3]) (m/enumerate [:a :b :c])))

(defflow gather-input-failure
  {:failure (comp :failed ex-data)}
  (m/gather (m/ap (throw (ex-info "this is fine." {:failed true}))) (m/enumerate [1 2 3])))

(defflow zip
  {:results (map =? [[1 :a] [2 :b] [3 :c]])}
  (m/zip vector (m/enumerate [1 2 3]) (m/enumerate [:a :b :c])))

(defflow zip-failure
  {:failure (comp :failed ex-data)}
  (m/zip (fn [_ _] (throw (ex-info "this is fine." {:failed true})))
         (m/enumerate [1 2 3]) (m/enumerate [:a :b :c])))

(defflow zip-input-failure
  {:failure (comp :failed ex-data)}
  (m/zip vector (m/ap (throw (ex-info "this is fine." {:failed true}))) (m/enumerate [1 2 3])))

(defn delay-each [delay input]
  (m/ap (m/? (m/sleep delay (m/?? input)))))

(defflow relieve
  {:results (map =? [24 79 67 61 99 37])
   :timeout 1000}
  (->> (m/ap (let [n (m/?? (m/enumerate [24 79 67 34 18 9 99 37]))]
               (m/? (m/sleep n n))))
       (m/relieve +)
       (delay-each 80)))

(defflow relieve-input-failure
  {:failure (comp :failed ex-data)}
  (->> (m/ap (throw (ex-info "this is fine." {:failed true})))
       (m/relieve +)
       (delay-each 80)))

(defflow buffer-small
  {:results (map =? (range 10))}
  (m/buffer 1 (m/enumerate (range 10))))

(defflow buffer-large
  {:results (map =? (range 10))}
  (m/buffer 20 (m/enumerate (range 10))))

(defflow buffer-input-failure
  {:failure (comp :failed ex-data)}
  (m/buffer 20 (m/ap (throw (ex-info "this is fine." {:failed true})))))

(deftask observe
  {:success (=? (range 5))
   :timeout 100}
  (let [e (m/dfv)]
    (m/join {}
            (m/sp
              (let [event! (m/? e)]
                (dotimes [i 5]
                  (m/? (m/sleep 10))
                  (event! i))))
            (->> (m/observe (fn [!] (e !) #()))
                 (m/transform (take 5))
                 (m/aggregate conj)))))

(deftask observe-callback-nop-when-done
  {:success any?}
  (m/sp
    (let [e (m/dfv)]
      (m/? (->> (m/observe (fn [!] (! nil) #(e !)))
                (m/transform (take 1))
                (m/aggregate conj)))
      ((m/? e) nil))))

(defflow observe-sub-failure
  {:failure (comp :failed ex-data)}
  (m/observe (fn [_] (throw (ex-info "this is fine." {:failed true})))))

(defflow observe-unsub-failure
  {:results [(=? nil)]}
  (m/transform (take 1) (m/observe (fn [!] (! nil) #(throw (ex-info "this is fine." {}))))))

(deftask watch
  {:success (=? (range 5))
   :timeout 100}
  (let [a (atom 0)]
    (m/join {}
            (m/sp
              (dotimes [_ 4]
                (m/? (m/sleep 10))
                (swap! a inc)))
            (->> (m/watch a)
                 (m/transform (take 5))
                 (m/aggregate conj)))))

(defn sleep-emit [delays]
  (m/ap
    (let [n (m/?? (m/enumerate delays))]
      (m/? (m/sleep n n)))))

(defflow latest
  {:results (map =? [[24 86] [24 12] [79 37] [67 37] [34 93]])
   :timeout 500}
  (delay-each 50 (m/latest vector (sleep-emit [24 79 67 34]) (sleep-emit [86 12 37 93]))))

(defflow sample-none
  {:results []}
  (m/sample {} (m/ap) m/none))

(defflow sample-sync
  {:results (map (comp =? vector) (range 10) (range 10))}
  (m/sample vector (m/enumerate (range 10)) (m/enumerate (range 10))))

(defflow sample-async
  {:results (map =? [[24 86] [24 12] [79 37] [67 93]])
   :timeout 500}
  (delay-each 50 (m/sample vector (sleep-emit [24 79 67 34]) (sleep-emit [86 12 37 93]))))

(defflow sample-unavailable-sampled
  {:failure any?}
  (m/sample {} m/none (m/enumerate (range))))

(defflow sample-exhausted-sampled
  {:results (map (comp =? vector) (concat (range 5) (repeat 4)) (range 10))}
  (m/sample vector (m/enumerate (range 5)) (m/enumerate (range 10))))

(defflow sample-exhausted-sampler
  {:results (map (comp =? vector) (range 5) (range 5))}
  (m/sample vector (m/enumerate (range 10)) (m/enumerate (range 5))))