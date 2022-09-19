(ns missionary.sem-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import [missionary Cancelled]))

(lc/defword release [id & events] [(l/dup) (l/change get id) (apply lc/call 0 events) (l/lose)])
(lc/defword acquire [id lock-id & events] [(l/dup) (l/change get id) (apply l/start lock-id events)])
(defn acquired [lock-id] (l/succeeded lock-id nil?))

(t/deftest simple
  (t/is (= []
          (lc/run
            (l/store
              (m/sem) (l/insert :sem)
              (acquire :sem :lock1 (acquired :lock1))
              (acquire :sem :lock2)
              (release :sem (acquired :lock2)))))))

(t/deftest more-tokens
  (t/is (= []
          (lc/run
            (l/store
              (m/sem 3) (l/insert :sem)
              (acquire :sem :lock1 (acquired :lock1))
              (acquire :sem :lock2 (acquired :lock2))
              (acquire :sem :lock3 (acquired :lock3))
              (acquire :sem :lock4)
              (release :sem (acquired :lock4)))))))

(t/deftest cancel
 (t/is (= []
          (lc/run
            (l/store
              (m/sem) (l/insert :sem)
              (acquire :sem :lock1 (acquired :lock1))
              (acquire :sem :lock2)
              (l/cancel :lock2 (l/failed :lock2 (partial instance? Cancelled))))))))

(defn sem
  ([] (lc/event :released))
  ([s _f] (s (lc/event :acquired))))

(t/deftest holding
  (t/is (= []
          (lc/run
            (l/store
              (m/sp (m/holding sem (lc/event :return-event)))
              (l/start :main
                (l/compose (l/check #{:acquired}) nil)
                (l/compose (l/check #{:return-event}) :val)
                (l/compose (l/check #{:released}) nil)
                (l/succeeded :main #{:val})))))))
