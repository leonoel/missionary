(ns missionary.test.buffer
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(t/deftest simple-with-cancel
  (t/is (= []
          (lc/run
            (l/store
              (m/buffer 2 (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/notify :input
                (l/transferred :input 0)
                (l/notified :main))
              (l/notify :input
                ;; buffer=2 and we have 1, so we transfer again
                (l/transferred :input 1))
              (l/notify :input
                ;; buffer full, no more transfer
                )
              (l/transfer :main
                (l/notified :main)
                ;; buffer has space and we were notified, so we transfer again
                (l/transferred :input 2))
              (l/check #{0})
              (l/transfer :main
                (l/notified :main))
              (l/check #{1})
              (l/transfer :main)
              (l/check #{2})
              (l/cancel :main
                (l/cancelled :input)))))))

(t/deftest input-terminates
  (t/is (= []
          (lc/run
            (l/store
              (m/buffer 2 (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/terminate :input
                (l/terminated :main))))))
  (t/testing "but there's still value to transfer"
    (t/is (= []
            (lc/run
              (l/store
                (m/buffer 2 (l/flow :input))
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

(t/deftest input-crashes
  (t/is (= []
          (lc/run
            (l/store
              (m/buffer 2 (l/flow :input))
              (l/spawn :main
                (l/spawned :input))
              (l/notify :input
                (l/crashed :input err)
                (l/notified :main))
              (l/crash :main)
              (l/check #{err}))))))
