(ns missionary.eduction-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(t/deftest cancel
  (t/is (= []
          (lc/run
            (l/store
              (m/eduction (map inc) (l/flow :input))
              (l/spawn :main
                ;; eduction is in charge of input, so it spawns it
                (l/spawned :input))
              (l/notify :input
                ;; as input notifies of a value eduction transfers is and applies xf,
                ;; notifying of new value
                (l/transferred :input
                  0)
                (l/notified :main))
              (l/transfer :main)
              (l/check #{1})
              (l/cancel :main
                ;; eduction is in charge of input, so it cancels it
                (l/cancelled :input)))))))

(t/deftest terminate
  (t/is (= []
          (lc/run
            (l/store
              (m/eduction (map inc) (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/terminate :input
                ;; if input terminates, so do we
                (l/terminated :main)))))))

(def err (ex-info "" {}))

(t/deftest crash-input
  (t/is (= []
          (lc/run
            (l/store
              (m/eduction (map inc) (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/notify :input
                ;; input crashing means we have to cancel it and notify of new value
                (l/crashed :input
                  err)
                (l/cancelled :input)
                (l/notified :main))
              (l/crash :main)
              (l/check #{err})
              (l/cancel :main
                (l/cancelled :input)))))))

(t/deftest transducer-throws
  (t/is (= []
          (lc/run
            (l/store
              (m/eduction (map (fn [_] (throw err))) (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/notify :input
                (l/transferred :input
                  0)
                ;; the eduction's xf throws, so it cancels input
                (l/cancelled :input)
                (l/notified :main))
              (l/crash :main)
              (l/check #{err}))))))

(t/deftest transducer-terminates-early
  (t/is (= []
          (lc/run
            (l/store
              (m/eduction (take 1) (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/notify :input
                (l/transferred :input
                  0)
                ;; the xf terminates (via `reduced`), so it cancels input
                (l/cancelled :input)
                (l/notified :main))
              (l/transfer :main)
              (l/check #{0}))))))

(t/deftest transducer-returns-multiple-values
  (t/is (= []
          (lc/run
            (l/store
              (m/eduction cat (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/notify :input
                (l/transferred :input
                  [1 2])
                (l/notified :main))
              (l/transfer :main
                ;; we get a new notification, since `cat` returned 2 values
                (l/notified :main))
              (l/check #{1})
              (l/transfer :main)
              (l/check #{2}))))))

(t/deftest transducer-swallows-values
  (t/is (= []
          (lc/run
            (l/store
              (m/eduction (filter odd?) (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/notify :input
                (l/transferred :input
                  0)
                ;; xf filters this value, so we don't notify from main
                )
              (l/notify :input
                (l/transferred :input
                  1)
                ;; xf keeps value, so we notify
                (l/notified :main))
              (l/transfer :main)
              (l/check #{1}))))))

(t/deftest transducer-produces-on-completion
  (t/is (= []
          (lc/run
            (l/store
              (m/eduction (partition-all 2) (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/notify :input
                (l/transferred :input
                  1))
              (l/terminate :input
                ;; input terminated, so partition-all completes with [1],
                ;; so it notifies of the last value
                (l/notified :main))
              (l/transfer :main
                ;; since this was the last value we terminate
                (l/terminated :main))
              (l/check #{[1]}))))))
