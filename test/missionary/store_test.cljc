(ns missionary.store-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import missionary.Cancelled))

(lc/defword append [s x & events]
  [x s
   (apply lc/call 1 events)
   (lc/drop 0)])

(lc/defword freeze [s & events]
  [s
   (apply lc/call 0 events)
   (lc/drop 0)])

(t/deftest append-read
  (t/is
    (= []
      (let [!x (m/store + 0)]
        (lc/run
          (l/store
            (append !x 2)
            (append !x 3)
            !x (l/spawn :r1 (l/notified :r1))
            (l/transfer :r1)
            (l/check #{5})
            (append !x 1 (l/notified :r1))
            (append !x 2)
            (l/transfer :r1)
            (l/check #{3})
            (l/cancel :r1 (l/notified :r1))
            (append !x 4)
            (l/crash :r1 (l/terminated :r1))
            (l/check #(instance? Cancelled %))
            !x (l/spawn :r2 (l/notified :r2))
            (append !x 5)
            (l/transfer :r2)
            (l/check #{17})))))))

(t/deftest freeze-before-read
  (t/is
    (= []
      (let [!x (m/store + 0)]
        (lc/run
          (l/store
            (freeze !x)
            !x (l/spawn :r1 (l/notified :r1))
            (l/transfer :r1 (l/terminated :r1))
            (l/check #{0})))))))

(t/deftest freeze-after-read
  (t/is
    (= []
      (let [!x (m/store + 0)]
        (lc/run
          (l/store
            !x (l/spawn :r1 (l/notified :r1))
            (l/transfer :r1)
            (l/check #{0})
            (freeze !x (l/terminated :r1))
            !x (l/spawn :r2 (l/notified :r2))
            (l/transfer :r2 (l/terminated :r2))
            (l/check #{0})))))))

(t/deftest freeze-during-transfer
  (t/is
    (= []
      (let [!x (m/store + 0)]
        (lc/run
          (l/store
            !x (l/spawn :r1 (l/notified :r1))
            (freeze !x)
            (l/transfer :r1 (l/terminated :r1))
            (l/check #{0})))))))

(t/deftest crash-during-tranfer
  (t/is
    (= []
      (let [!x (m/store (fn [_ _] (throw (ex-info "boom" {:flag true}))) nil)]
        (lc/run
          (l/store
            !x (l/spawn :r1 (l/notified :r1))
            (append !x nil)
            (append !x nil)
            (l/crash :r1 (l/terminated :r1))
            (l/check #(:flag (ex-data %)))
            !x (l/spawn :r2 (l/notified :r2))
            (l/crash :r2 (l/terminated :r2))
            (l/check #(:flag (ex-data %)))))))))