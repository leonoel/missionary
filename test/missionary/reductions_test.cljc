(ns missionary.reductions-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(lc/defword init [flow] [flow (l/spawn :main (l/spawned :input) (l/notified :main))])

(t/deftest simple-with-cancel
  (t/is (= []
          (lc/run
            (l/store
              (init (m/reductions + 1 (l/flow :input)))
              (l/transfer :main)
              (l/check #{1})
              (l/notify :input
                (l/notified :main))
              (l/transfer :main
                (l/transferred :input 2))
              (l/check #{3})
              (l/notify :input
                (l/notified :main))
              (l/transfer :main
                (l/transferred :input 3))
              (l/check #{6})
              (l/cancel :main
                ;; reductions is in charge of input, so it cancels it
                (l/cancelled :input)))))))

(t/deftest init-terminates-before-main-transfers
  (t/is (= []
          (lc/run
            (l/store
              (init (m/reductions + 1 (l/flow :input)))
              ;; we don't terminate yet, since init wasn't transfered
              (l/terminate :input)
              (l/transfer :main  ;; now we do
                (l/terminated :main))
              (l/check #{1}))))))

(t/deftest terminate
  (t/is (= []
          (lc/run
            (l/store
              (init (m/reductions + 1 (l/flow :input)))
              (l/transfer :main)
              (l/check #{1})
              (l/terminate :input
                (l/terminated :main)))))))

(def err (ex-info "" {}))

(t/deftest reducer-throws
  (t/is (= []
          (lc/run
            (l/store
              (init (m/reductions (fn [_ _] (throw err)) 1 (l/flow :input)))
              (l/transfer :main)
              (l/check #{1})
              (l/notify :input
                (l/notified :main))
              (l/crash :main
                (l/transferred :input 2)
                (l/cancelled :input))
              (l/check #{err}))))))

(t/deftest crash-input
  (t/is (= []
          (lc/run
            (l/store
              (init (m/reductions + 1 (l/flow :input)))
              (l/transfer :main)
              (l/check #{1})
              (l/notify :input
                (l/notified :main))
              (l/crash :main
                (l/crashed :input err)
                (l/cancelled :input))
              (l/check #{err})))))
  (t/testing "transfer init value after being notified from input"
    (t/is (= []
            (lc/run
              (l/store
                (init (m/reductions + 1 (l/flow :input)))
                (l/notify :input)
                (l/transfer :main
                  (l/notified :main))
                (l/check #{1})
                (l/crash :main
                  (l/crashed :input err)
                  (l/cancelled :input))
                (l/check #{err})))))))
