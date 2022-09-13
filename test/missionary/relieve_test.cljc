(ns missionary.relieve-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(t/deftest simple-with-cancel
  (t/is (= []
          (lc/run
            (l/store
              (m/relieve + (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/notify :input
                (l/transferred :input 1)
                (l/notified :main))
              (l/transfer :main)
              (l/check #{1})
              (l/cancel :main
                (l/cancelled :input)))))))

(def reduced! (l/compose (l/check #{:reduced}) nil))

(t/deftest overflow
  (t/is (= []
          (lc/run
            (l/store
              (m/relieve (fn [ac nx] (lc/event :reduced) (+ ac nx)) (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/notify :input
                (l/transferred :input 1)
                (l/notified :main))
              (l/notify :input
                (l/transferred :input 2)
                reduced!)
              (l/notify :input
                (l/transferred :input 3)
                reduced!)
              (l/transfer :main)
              (l/check #{6}))))))

(t/deftest terminate
  (t/is (= []
          (lc/run
            (l/store
              (m/relieve + (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/terminate :input
                (l/terminated :main))))))
  (t/testing "but there's still value to transfer"
    (t/is (= []
            (lc/run
              (l/store
                (m/relieve + (l/flow :input))
                (l/spawn :main
                  (l/spawned :input))
                (l/notify :input
                  (l/transferred :input 1)
                  (l/notified :main))
                (l/terminate :input)
                (l/transfer :main
                  (l/terminated :main))
                (l/check #{1})))))))

(def err (ex-info "" {}))

(t/deftest rf-throws
  (t/is (= []
          (lc/run
            (l/store
              (m/relieve (fn [_ _] (throw err)) (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/notify :input
                (l/transferred :input :doesnt-matter)
                (l/notified :main))
              (l/notify :input
                (l/transferred :input 0)
                (l/cancelled :input))
              (l/crash :main)
              (l/check #{err}))))))
