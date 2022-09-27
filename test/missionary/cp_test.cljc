(ns missionary.cp-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :refer [cp ?<]]
            [clojure.test :refer [deftest is]]))

(def err (ex-info "" {}))

(deftest pure-success
  (is (= []
        (lc/run
          (l/store
            (cp 1)
            (l/spawn :main
              (l/notified :main))
            (l/transfer :main
              (l/terminated :main))
            (l/check #{1}))))))

(deftest pure-failure
  (is (= []
        (lc/run
          (l/store
            (cp (throw err))
            (l/spawn :main
              (l/notified :main))
            (l/crash :main
              (l/terminated :main))
            (l/check #{err}))))))

(deftest ident-success
  (is (= []
        (lc/run
          (l/store
            (cp (?< (l/flow :input)))
            (l/spawn :main
              (l/notified :main))
            (l/transfer :main
              (l/spawned :input
                (l/notify :input))
              (l/transferred :input
                (l/terminate :input)
                0)
              (l/terminated :main))
            (l/check #{0}))))))

(deftest ident-failure
  (is (= [] (lc/run
              (l/store
                (cp (?< (l/flow :input)))
                (l/spawn :main
                  (l/notified :main))
                (l/crash :main
                  (l/spawned :input
                    (l/notify :input))
                  (l/crashed :input
                    (l/terminate :input)
                    err)
                  (l/terminated :main))
                (l/check #{err}))))))

(deftest ident-duplicate
  (is (= []
        (lc/run
          (l/store
            (cp (l/detect :result (?< (l/flow :input))))
            (l/spawn :main
              (l/notified :main))
            (l/transfer :main
              (l/spawned :input
                (l/notify :input))
              (l/transferred :input
                0)
              (l/detected :result))
            (l/check #{0})
            (l/notify :input
              (l/notified :main))
            (l/transfer :main
              (l/transferred :input
                (l/terminate :input)
                0)
              (l/terminated :main))
            (l/check #{0}))))))

(deftest lazy-switch
  (is (= []
        (lc/run
          (l/store                         ;; [{}]
            (cp (?< (?< (l/flow :parent)))) ;; [{} (cp)]
            (l/spawn :main
              (l/notified :main)) ;; [{:main cp-iterator}]
            (l/transfer :main
              (l/spawned :parent
                (l/notify :parent))
              (l/transferred :parent
                (l/flow :child1))
              (l/spawned :child1
                (l/notify :child1))
              (l/transferred :child1
                1))
            (l/check #{1})
            (l/notify :parent
              (l/notified :main))
            (l/transfer :main
              (l/transferred :parent
                (l/flow :child2))
              (l/cancelled :child1
                (l/terminate :child1))
              (l/spawned :child2
                (l/notify :child2))
              (l/transferred :child2
                2))
            (l/check #{2})
            (l/terminate :child2)
            (l/terminate :parent
              (l/terminated :main)))))))

(deftest input-change-from-continuation
  (is (= []
        (lc/run
          (l/store
            (cp (lc/event (?< (l/flow :input))))
            (l/spawn :main
              (l/notified :main))
            (l/transfer :main
              (l/spawned :input
                (l/notify :input))
              (l/transferred :input :x1)
              (l/compose
                (l/check #{:x1})
                (l/notify :input)
                nil)
              (l/transferred :input :x2)
              (l/compose
                (l/check #{:x2})
                :result))
            (l/check #{:result}))))))

(deftest undefined-input
  (is (= []
        (lc/run
          (l/store
            (cp (?< (l/flow :input)))
            (l/spawn :main
              (l/notified :main))
            (l/crash :main
              (l/spawned :input)
              (l/cancelled :input
                (l/notify :input
                  (l/crashed :input
                    (l/compose (l/terminate :input) err))))
              (l/terminated :main))
            (l/check (comp #{"Undefined continuous flow."} ex-message)))))))

(deftest switch-both-ready
  (is (= []
        (lc/run
          (l/store
            (cp (?< (?< (l/flow :parent))))
            (l/spawn :main
              (l/notified :main))
            (l/transfer :main
              (l/spawned :parent
                (l/notify :parent))
              (l/transferred :parent
                (l/flow :child1))
              (l/spawned :child1
                (l/notify :child1))
              (l/transferred :child1 1))
            (l/check #{1})
            (l/notify :parent
              (l/notified :main))
            (l/notify :child1)
            (l/transfer :main
              (l/transferred :parent
                (l/flow :child2))
              (l/cancelled :child1)
              (l/spawned :child2
                (l/notify :child2))
              (l/transferred :child2 2)
              (l/transferred :child1
                (l/terminate :child1)
                err))
            (l/check #{2}))))))