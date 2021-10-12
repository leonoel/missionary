(ns missionary.core-test
  (:require
    [missionary.core :as m :include-macros true]
    [missionary.tck :refer [if-try deftask defflow] :include-macros true]))

(def =? (partial partial =))
(def fine! #(throw (ex-info "this is fine." {:fine true})))
(def fine? (comp :fine ex-data))
(def evil-keys "A vector of 1024 different values sharing the same hash.
https://stackoverflow.com/questions/12925988/how-to-generate-strings-that-share-the-same-hashcode-in-java"
  (nth (iterate (partial into [] (mapcat (juxt (partial str "Aa") (partial str "BB")))) [""]) 10))

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
    (let [x (m/?= (m/seed [19 57 28 6 87]))]
      (m/? (m/sleep x x)))))

(defflow debounce
  {:results (map =? [24 79 9 37])
   :timeout 500}
  (letfn [(deb [delay flow]
            (m/ap (let [x (m/?< flow)]
                    (if-try [x (m/? (m/sleep delay x))]
                      x (m/?> m/none)))))]
    (->> (m/ap (let [n (m/?> (m/seed [24 79 67 34 18 9 99 37]))]
                 (m/? (m/sleep n n))))
         (deb 50))))

(deftask ambiguous-eval-order
  {:success (=? [[1 3] [2 4]])
   :timeout 10}
  (let [counter (partial swap! (atom 0) inc)]
    (m/reduce (fn [r x] (conj r [x (counter)])) []
      (m/ap (m/? (m/sleep (m/amb> 0 0) (counter)))))))

(deftask aggregate
  {:success (=? [1 2 3])}
  (m/reduce conj (m/seed [1 2 3])))

(deftask aggregate-reduced
  {:success (=? true)}
  (m/reduce (fn [_ _] (reduced true)) nil (m/seed [1 2 3])))

(deftask aggregate-failure
  {:failure fine?}
  (m/reduce (fn [_ _] (fine!)) nil (m/ap)))

(deftask aggregate-input-failure
  {:failure fine?}
  (m/reduce conj (m/ap (fine!))))

(defflow integrate
  {:results (map =? [[] [1] [1 2] [1 2 3]])}
  (m/reductions conj (m/seed [1 2 3])))

(defflow integrate-reduced
  {:results (map =? [nil true])}
  (m/reductions (fn [_ _] (reduced true)) nil (m/seed [1 2 3])))

(defflow integrate-failure
  {:results [(=? nil)]
   :failure fine?}
  (m/reductions (fn [_ _] (fine!)) nil (m/seed [1 2 3])))

(defflow integrate-input-failure
  {:results [(=? [])]
   :failure fine?}
  (m/reductions conj (m/ap (fine!))))

(defflow transform
  {:results (map =? (range 10))}
  (m/eduction identity (m/seed (range 10))))

(defflow transform-shrinking
  {:results (map =? [[0 0 1 2] [0 1 2 3] [4 0 1 2] [3 4 5 6] [0 1 2 3] [4 5 6 7] [8]])}
  (m/eduction (filter odd?) (mapcat range) (partition-all 4) (m/seed (range 10))))

(defflow transform-expanding
  {:results (map =? [0 0 1 2 0 1 2 3 4])}
  (m/eduction (filter odd?) (mapcat range) (take 9) (m/seed (range 10))))

(defflow transform-failure
  {:failure fine?}
  (m/eduction (map (fn [_] (fine!))) (m/ap)))

(defflow transform-input-failure
  {:failure fine?}
  (m/eduction identity (m/ap (fine!))))

(defflow gather
  {:results (map =? [1 :a 2 :b 3 :c])}
  (m/gather (m/seed [1 2 3]) (m/seed [:a :b :c])))

(defflow gather-input-failure
  {:failure fine?}
  (m/gather (m/ap (fine!)) (m/seed [1 2 3])))

(defflow zip
  {:results (map =? [[1 :a] [2 :b] [3 :c]])}
  (m/zip vector (m/seed [1 2 3]) (m/seed [:a :b :c])))

(defflow zip-failure
  {:failure fine?}
  (m/zip (fn [_ _] (fine!))
    (m/seed [1 2 3]) (m/seed [:a :b :c])))

(defflow zip-input-failure
  {:failure fine?}
  (m/zip vector (m/ap (fine!)) (m/seed [1 2 3])))

(defn delay-each [delay input]
  (m/ap (m/? (m/sleep delay (m/?> input)))))

(defflow relieve
  {:results (map =? [24 79 67 61 99 37])
   :timeout 1000}
  (->> (m/ap (let [n (m/?> (m/seed [24 79 67 34 18 9 99 37]))]
               (m/? (m/sleep n n))))
    (m/relieve +)
    (delay-each 80)))

(defflow relieve-input-failure
  {:failure fine?}
  (->> (m/ap (fine!))
       (m/relieve +)
       (delay-each 80)))

(defflow buffer-small
  {:results (map =? (range 10))}
  (m/buffer 1 (m/seed (range 10))))

(defflow buffer-large
  {:results (map =? (range 10))}
  (m/buffer 20 (m/seed (range 10))))

(defflow buffer-input-failure
  {:failure fine?}
  (m/buffer 20 (m/ap (fine!))))

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
              (m/eduction (take 5))
              (m/reduce conj)))))

(deftask observe-callback-nop-when-done
  {:success any?}
  (m/sp
    (let [e (m/dfv)]
      (m/? (->> (m/observe (fn [!] (! nil) #(e !)))
                (m/eduction (take 1))
                (m/reduce conj)))
      ((m/? e) nil))))

(defflow observe-sub-failure
  {:failure fine?}
  (m/observe (fn [_] (fine!))))

(defflow observe-unsub-failure
  {:results [(=? nil)]}
  (m/eduction (take 1) (m/observe (fn [!] (! nil) fine!))))

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
                 (m/eduction (take 5))
                 (m/reduce conj)))))

(defn sleep-emit [delays]
  (m/ap
    (let [n (m/?> (m/seed delays))]
      (m/? (m/sleep n n)))))

(defflow latest
  {:results (map =? [[24 86] [24 12] [79 37] [67 37] [34 93]])
   :timeout 500}
  (delay-each 50 (m/latest vector (sleep-emit [24 79 67 34]) (sleep-emit [86 12 37 93]))))

(defflow latest-seed
  {:results (map =? (range 10))}
  (m/latest identity (m/seed (range 10))))

(defflow sample-none
  {:results []}
  (m/sample {} (m/ap) m/none))

(defflow sample-sync
  {:results (map (comp =? vector) (range 10) (range 10))}
  (m/sample vector (m/seed (range 10)) (m/seed (range 10))))

(defflow sample-async
  {:results (map =? [[24 86] [24 12] [79 37] [67 93]])
   :timeout 500}
  (delay-each 50 (m/sample vector (sleep-emit [24 79 67 34]) (sleep-emit [86 12 37 93]))))

(defflow sample-unavailable-sampled
  {:failure any?}
  (m/sample {} m/none (m/seed (range))))

(defflow sample-exhausted-sampled
  {:results (map (comp =? vector) (concat (range 5) (repeat 4)) (range 10))}
  (m/sample vector (m/seed (range 5)) (m/seed (range 10))))

(defflow sample-exhausted-sampler
  {:results (map (comp =? vector) (range 5) (range 5))}
  (m/sample vector (m/seed (range 10)) (m/seed (range 5))))

(deftask reactor-no-publisher
  {:success nil?}
  (m/reactor))

(deftask reactor-self-termination
  {:success any?}
  (m/reactor (m/stream! (m/ap))))

(deftask reactor-cancelled
  {:success any?
   :cancel 0}
  (m/reactor (m/signal! (m/ap))))

(deftask reactor-boot-crash
  {:failure fine?}
  (m/reactor-call fine!))

(deftask reactor-stream-crash
  {:failure fine?}
  (m/reactor (m/stream! (m/ap (fine!)))))

(deftask reactor-signal-crash
  {:failure fine?}
  (m/reactor (m/stream! (m/signal! (m/ap (fine!))))))

(deftask reactor-stream-crash-delayed
  {:failure fine?
   :timeout 10}
  (m/reactor (m/stream! (m/stream! (m/ap (m/? (m/sleep 0)) (fine!))))))

(deftask reactor-stream-cancel
  {:success nil?}
  (m/reactor ((m/stream! (m/ap)))))

(deftask reactor-signal-cancel
  {:success nil?}
  (m/reactor ((m/signal! (m/ap)))))

(deftask reactor-double-sub
  {:success (comp (=? [1]) deref)
   :cancel  0}
  (m/reactor
    (let [r (atom [])
          i (m/signal! (m/watch (atom 1)))]
      (m/stream! (m/ap (m/?> i) (swap! r conj (m/?> i)))) r)))

(deftask reactor-delayed
  {:success (comp (=? [1 2]) deref)
   :timeout 10}
  (m/reactor
    (let [r  (atom [])
          i1 (m/stream! (m/ap (m/? (m/sleep 2 1))))
          i2 (m/stream! (m/ap (m/? (m/sleep 1 2))))]
      (m/stream! (m/zip (partial swap! r conj) i1 i2)) r)))

(deftask groupby
  {:success (=? (apply + (range 971)))}
  (m/reduce +
    (m/ap
      (->> (m/seed (shuffle (range 971)))
        (m/group-by #(evil-keys (mod % 167)))
        (m/?=)
        (val)
        (m/eduction (take 13))
        (m/reduce +)
        (m/?)))))

(defflow groupby-input-failure
  {:failure fine?}
  (m/group-by {} (m/ap (fine!))))

(defflow groupby-keyfn-failure
  {:failure fine?}
  (m/group-by (fn [_] (fine!)) (m/seed (range))))