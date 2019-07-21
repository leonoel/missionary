(ns missionary.core-test
  (:require
    [missionary.core :as m]
    [missionary.helpers #?(:clj :refer :cljs :refer-macros) [deftest* failing?]]))

(deftest* timing
  (assert (= nil (m/? (m/sleep 0))))
  (assert (failing? (m/? (m/timeout 0 (m/sleep 100))))))

(deftest* semaphore
  (let [sem (m/sem 7)]
    (assert (failing? (m/? (->> (m/sp (while true (m/holding sem (m/? (m/sleep 0)))))
                                (repeat 100)
                                (apply m/join vector)
                                (m/timeout 100)))))
    (dotimes [_ 7] (m/? (m/timeout 10 sem)))))

(deftest* rendezvous
  (let [rdv (m/rdv)]
    (assert (failing? (m/? (->> (m/sp (while true
                                        (m/? (m/compel (m/join vector rdv (rdv false))))
                                        (m/? (m/sleep 0))))
                                (repeat 100)
                                (apply m/join vector)
                                (m/timeout 100)))))
    (assert (failing? (m/? (m/timeout 10 rdv))))))

(deftest* mailbox
  (let [mbx (m/mbx)]
    (assert (failing? (m/? (->> (m/sp (while true
                                        (mbx false)
                                        (m/? (m/compel mbx))
                                        (m/? (m/sleep 0))))
                                (repeat 100)
                                (apply m/join)
                                (m/timeout 100)))))
    (assert (failing? (m/? (m/timeout 10 mbx))))))

(deftest* dataflow
  (let [dfv (m/dfv)]
    (assert (dfv true))
    (assert (m/? dfv))))

(deftest* ambiguous
  (letfn [(debounce [delay flow]
            (m/ap (let [x (m/?! flow)]
                    (try (m/? (m/sleep delay x))
                         (catch #?(:clj Throwable :cljs :default) _ (m/?? m/none))))))]
    (assert (= [24 79 9 37]
               (m/? (->> (m/ap (let [n (m/?? (m/enumerate [24 79 67 34 18 9 99 37]))]
                                 (m/? (m/sleep n n))))
                         (debounce 50)
                         (m/aggregate conj)))))))

(deftest* aggregate
  (assert (= [1 2 3] (m/? (m/aggregate conj (m/enumerate [1 2 3])))))
  (assert (m/? (m/aggregate (fn [_ _] (reduced true)) nil (m/enumerate [1 2 3]))))
  (assert (failing? (m/? (m/aggregate (fn [_ _] (throw (ex-info "this is fine." {}))) nil (m/ap)))))
  (assert (failing? (m/? (m/aggregate conj (m/ap (throw (ex-info "this is fine." {}))))))))

(deftest* integrate
  (assert (= [[] [1] [1 2] [1 2 3]]
             (m/? (->> (m/enumerate [1 2 3])
                       (m/integrate conj)
                       (m/aggregate conj)))))
  (assert (= [nil true]
             (m/? (->> (m/enumerate [1 2 3])
                       (m/integrate (fn [_ _] (reduced true)) nil)
                       (m/aggregate conj)))))
  (assert (failing? (m/? (->> (m/enumerate [1 2 3])
                              (m/integrate (fn [_ _] (throw (ex-info "this is fine." {}))) nil)
                              (m/aggregate conj)))))
  (assert (failing? (m/? (->> (m/ap (throw (ex-info "this is fine." {})))
                              (m/integrate conj)
                              (m/aggregate conj))))))

(deftest* transform
  (assert (= [[0 0 1 2] [0 1 2 3] [4 0 1 2] [3 4 5 6] [0 1 2 3] [4 5 6 7] [8]]
             (m/? (->> (m/enumerate (range 10))
                       (m/transform (comp (filter odd?) (mapcat range) (partition-all 4)))
                       (m/aggregate conj)))))
  (assert (= [0 0 1 2 0 1 2 3 4]
             (m/? (->> (m/enumerate (range 10))
                       (m/transform (comp (filter odd?) (mapcat range) (take 9)))
                       (m/aggregate conj)))))
  (assert (failing? (m/? (->> (m/ap (throw (ex-info "this is fine." {})))
                              (m/transform identity)
                              (m/aggregate conj)))))
  (assert (failing? (m/? (->> (m/ap)
                              (m/transform (map (fn [_] (throw (ex-info "this is fine." {})))))
                              (m/aggregate conj))))))

(deftest* gather
  (assert (= [1 :a 2 :b 3 :c]
             (m/? (->> (m/gather (m/enumerate [1 2 3])
                                 (m/enumerate [:a :b :c]))
                       (m/aggregate conj)))))
  (assert (failing? (m/? (->> (m/gather (m/ap (throw (ex-info "this is fine." {})))
                                        (m/enumerate [1 2 3]))
                              (m/aggregate conj))))))

(deftest* zip
  (assert (= [[1 :a] [2 :b] [3 :c]]
             (m/? (->> (m/zip vector
                              (m/enumerate [1 2 3])
                              (m/enumerate [:a :b :c]))
                       (m/aggregate conj)))))
  (assert (failing? (m/? (->> (m/zip (fn [_ _] (throw (ex-info "this is fine." {})))
                                     (m/enumerate [1 2 3])
                                     (m/enumerate [:a :b :c]))
                              (m/aggregate conj)))))
  (assert (failing? (m/? (->> (m/zip vector
                                     (m/ap (throw (ex-info "this is fine." {})))
                                     (m/enumerate [1 2 3]))
                              (m/aggregate conj))))))

(deftest* relieve
  (letfn [(delay-each [delay input]
            (m/ap (m/? (m/sleep delay (m/?? input)))))]
    (assert (= [24 79 67 61 99 37]
               (m/? (->> (m/ap (let [n (m/?? (m/enumerate [24 79 67 34 18 9 99 37]))]
                                 (m/? (m/sleep n n))))
                         (m/relieve +)
                         (delay-each 80)
                         (m/aggregate conj)))))
    (assert (failing?
              (m/? (->> (m/ap (throw (ex-info "this is fine." {})))
                        (m/relieve +)
                        (delay-each 80)
                        (m/aggregate conj)))))))

(deftest* buffer
  (assert (= (range 10)
             (m/? (->> (m/enumerate (range 10))
                       (m/buffer 20)
                       (m/aggregate conj)))))
  (assert (= (range 10)
             (m/? (->> (m/enumerate (range 10))
                       (m/buffer 1)
                       (m/aggregate conj)))))
  (assert (failing? (m/? (->> (m/ap (throw (ex-info "this is fine." {})))
                              (m/buffer 20)
                              (m/aggregate conj))))))

(deftest* watch
  (assert (= [0 1 2 3 4]
             (let [a (atom 0)]
               (m/? (m/join {}
                            (m/sp
                              (dotimes [_ 4]
                                (m/? (m/sleep 10))
                                (swap! a inc)))
                            (->> (m/watch a)
                                 (m/transform (take 5))
                                 (m/aggregate conj))))))))

(deftest* latest
  (letfn [(sleep-emit [delays]
            (m/ap (let [n (m/?? (m/enumerate delays))]
                    (m/? (m/sleep n n)))))
          (delay-each [delay input]
            (m/ap (m/? (m/sleep delay (m/?? input)))))]
    (assert (= [[24 86] [24 12] [79 37] [67 37] [34 93]]
               (m/? (->> (m/latest vector
                                   (sleep-emit [24 79 67 34])
                                   (sleep-emit [86 12 37 93]))
                         (delay-each 50)
                         (m/aggregate conj)))))))

(deftest* sample
  (comment TODO)
  )