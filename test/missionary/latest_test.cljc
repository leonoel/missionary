(ns missionary.latest-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(lc/defword init [flow]
  [flow (l/spawn :main
          (l/spawned :x (l/notify :x))
          (l/spawned :y (l/notify :y))
          (l/notified :main))])

(t/deftest simple-with-cancel
  (t/is (= []
          (lc/run
            (l/store
              (init (m/latest vector (l/flow :x) (l/flow :y)))
              (l/transfer :main
                (l/transferred :x :x1)
                (l/transferred :y :y1))
              (l/check #{[:x1 :y1]})
              (l/cancel :main
                (l/cancelled :x)
                (l/cancelled :y)))))))

(t/deftest latest-is-retained
  (t/is (= []
          (lc/run
            (l/store
              (init (m/latest vector (l/flow :x) (l/flow :y)))
              (l/transfer :main
                (l/transferred :x :x1)
                (l/transferred :y :y1))
              (l/check #{[:x1 :y1]})
              (l/notify :x
                (l/notified :main))
              (l/transfer :main
                (l/transferred :x :x2))
              (l/check #{[:x2 :y1]}))))))

(t/deftest consecutive-notify-causes-retransfer
  (t/is (= []
          (lc/run
            (l/store
              (init (m/latest vector (l/flow :x) (l/flow :y)))
              (l/transfer :main
                (l/transferred :x (l/notify :x) :x1)
                (l/transferred :x :x2)
                (l/transferred :y :y1))
              (l/check #{[:x2 :y1]}))))))

(def err (ex-info "" {}))

(t/deftest input-crashes
  (t/is (= []
          (lc/run
            (l/store
              (init (m/latest vector (l/flow :x) (l/flow :y)))
              (l/crash :main
                (l/crashed :x err)
                (l/cancelled :x)
                (l/cancelled :y)
                (l/transferred :y :y1))
              (l/check #{err}))))))

(lc/defword f-called []
  [(l/check #{:f}) nil])

(t/deftest f-crashes
  (t/is (= []
          (lc/run
            (l/store
              (init (m/latest (fn [& _] (lc/event :f) (throw err)) (l/flow :x) (l/flow :y)))
              (l/crash :main
                (l/transferred :x :x1)
                (l/transferred :y :y1)
                (f-called)
                (l/cancelled :x)
                (l/cancelled :y))
              (l/check #{err}))))))

(t/deftest input-terminates
  (t/is (= []
          (lc/run
            (l/store
              (init (m/latest vector (l/flow :x) (l/flow :y)))
              (l/transfer :main
                (l/transferred :x :x1)
                (l/transferred :y :y1))
              (l/check #{[:x1 :y1]})
              (l/terminate :x)
              (l/terminate :y
                (l/terminated :main)))))))

(t/deftest input-change-from-combinator
  (t/is (= []
          (lc/run
            (l/store
              (m/latest lc/event (l/flow :x))
              (l/spawn :main
                (l/spawned :x (l/notify :x))
                (l/notified :main))
              (l/transfer :main
                (l/transferred :x :x1)
                (l/compose
                  (l/check #{:x1})
                  (l/notify :x)
                  :res)
                (l/notified :main))
              (l/check #{:res})
              (l/transfer :main
                (l/transferred :x :x2)
                (l/compose
                  (l/check #{:x2})
                  :res))
              (l/check #{:res}))))))

(t/deftest zero-input
  (t/is (= []
          (lc/run
            (l/store
              (m/latest (fn [] (lc/event :f)))
              (l/spawn :main
                (l/notified :main))
              (l/transfer :main
                (f-called)
                (l/terminated :main))
              (l/check nil?))))))