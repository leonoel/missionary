(ns missionary.reduce-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(t/deftest success
  (t/is (= []
          (lc/run
            (l/store
              (m/reduce conj (l/flow :input))
              (l/start :main (l/spawned :input))
              (l/notify :input (l/transferred :input 1))
              (l/notify :input (l/transferred :input 2))
              (l/terminate :input
                (l/succeeded :main #{[1 2]})))))))

(def err (ex-info "" {}))
(t/deftest flow-throws
    (t/is (= []
            (lc/run
              (l/store
                (m/reduce conj (l/flow :input))
                (l/start :main (l/spawned :input))
                (l/notify :input
                  (l/crashed :input err)
                  (l/cancelled :input))
                (l/terminate :input
                  (l/failed :main #{err})))))))

(t/deftest rf-throws
    (t/is (= []
            (lc/run
              (l/store
                (m/reduce (fn [_ _] (throw err)) [] (l/flow :input))
                (l/start :main (l/spawned :input))
                (l/notify :input
                  (l/transferred :input 1)
                  (l/cancelled :input))
                (l/terminate :input
                  (l/failed :main #{err})))))))

(t/deftest rf-init-throws
    (t/is (= []
            (lc/run
              (l/store
                (m/reduce (fn ([] (throw err)) ([a b] (conj a b))) (l/flow :input))
                (l/start :main
                  (l/spawned :input)
                  (l/cancelled :input))
                (l/terminate :input
                  (l/failed :main #{err})))))))

(t/deftest rf-returns-reduced
    (t/is (= []
            (lc/run
              (l/store
                (m/reduce (fn [_ _] (reduced :done)) [] (l/flow :input))
                (l/start :main (l/spawned :input))
                (l/notify :input
                  (l/transferred :input 1)
                  (l/cancelled :input))
                (l/terminate :input
                  (l/succeeded :main #{:done})))))))

(t/deftest cancel
  (t/testing "success"
    (t/is (= []
            (lc/run
              (l/store
                (m/reduce conj (l/flow :input))
                (l/start :main (l/spawned :input))
                (l/cancel :main
                  (l/cancelled :input))
                (l/notify :input
                  (l/transferred :input 1))
                (l/terminate :input
                  (l/succeeded :main #{[1]})))))))
  (t/testing "crash"
    (t/is (= []
            (lc/run
              (l/store
                (m/reduce conj (l/flow :input))
                (l/start :main (l/spawned :input))
                (l/cancel :main
                  (l/cancelled :input))
                (l/notify :input
                  (l/crashed :input err)
                  (l/cancelled :input))
                (l/terminate :input
                  (l/failed :main #{err}))))))))
