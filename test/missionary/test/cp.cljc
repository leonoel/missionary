(ns missionary.test.cp
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :refer [cp ?<]]
            [clojure.test :refer [deftest]]))

(def err (ex-info "" {}))

(deftest pure-success
  (lc/run [(cp 1)]
    (l/spawn :main
      (l/notified :main))
    (l/transfer
      (l/terminated :main))
    (l/check #{1})
    (lc/drop 0)))

(deftest pure-failure
  (lc/run [(cp (throw err))]
    (l/spawn :main
      (l/notified :main))
    (l/crash
      (l/terminated :main))
    (l/check #{err})
    (lc/drop 0)))

(deftest ident-success
  (lc/run [(cp (?< (l/flow :input)))]
    (l/spawn :main
      (l/notified :main))
    (l/transfer
      (l/spawned :input
        (l/signal))
      (l/transferred :input
        (l/terminate)
        (lc/push 0))
      (l/terminated :main))
    (l/check #{0})
    (lc/drop 0)))

(deftest ident-failure
  (lc/run [(cp (?< (l/flow :input)))]
    (l/spawn :main
      (l/notified :main))
    (l/crash
      (l/spawned :input
        (l/signal))
      (l/crashed :input
        (l/terminate)
        (lc/push err))
      (l/terminated :main))
    (l/check #{err})
    (lc/drop 0)))

(deftest ident-duplicate
  (lc/run [(cp (l/detect :result (?< (l/flow :input))))]
    (l/spawn :main
      (l/notified :main))
    (l/transfer
      (l/spawned :input
        (l/signal))
      (l/transferred :input
        (lc/push 0))
      (l/detected :result))
    (l/check #{0})
    (l/signal
      (l/notified :main))
    l/swap
    (l/transfer
      (l/transferred :input
        l/swap
        (l/terminate)
        (lc/push 0))
      (l/terminated :main))
    (l/check #{0})
    (lc/drop 0)))

(deftest lazy-switch
  (lc/run [(cp (?< (?< (l/flow :parent))))]
    (l/spawn :main
      (l/notified :main))
    (l/transfer
      (l/spawned :parent
        (l/signal))
      (l/transferred :parent
        (lc/push (l/flow :child1)))
      (l/spawned :child1
        (l/signal))
      (l/transferred :child1
        (lc/push 1)))
    (l/check #{1})
    l/swap
    (l/signal
      (l/notified :main))
    l/swap
    (lc/copy 2)
    (lc/drop 3)
    (l/transfer
      (l/transferred :parent
        (lc/push (l/flow :child2)))
      (l/cancelled :child1
        l/swap (l/terminate))
      (l/spawned :child2
        (l/signal))
      (l/transferred :child2
        (lc/push 2)))
    (l/check #{2})
    (l/terminate)
    (l/terminate
      (l/terminated :main))))
