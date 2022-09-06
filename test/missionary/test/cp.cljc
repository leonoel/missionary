(ns missionary.test.cp
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :refer [cp ?<]]
            [clojure.test :refer [deftest]]))

(def err (ex-info "" {}))

(deftest pure-success
  (lc/run []
    (l/store
      (lc/push (cp 1))
      (l/spawn :main
        (l/notified :main))
      (l/transfer :main
        (l/terminated :main))
      (l/check #{1}))))

(deftest pure-failure
  (lc/run []
    (l/store
      (lc/push (cp (throw err)))
      (l/spawn :main
        (l/notified :main))
      (l/crash :main
        (l/terminated :main))
      (l/check #{err}))))

(deftest ident-success
  (lc/run []
    (l/store
      (lc/push (cp (?< (l/flow :input))))
      (l/spawn :main
        (l/notified :main))
      (l/transfer :main
        (l/spawned :input
          (l/notify :input))
        (l/transferred :input
          (l/terminate :input)
          (lc/push 0))
        (l/terminated :main))
      (l/check #{0}))))

(deftest ident-failure
  (lc/run []
    (l/store
      (lc/push (cp (?< (l/flow :input))))
      (l/spawn :main
        (l/notified :main))
      (l/crash :main
        (l/spawned :input
          (l/notify :input))
        (l/crashed :input
          (l/terminate :input)
          (lc/push err))
        (l/terminated :main))
      (l/check #{err}))))

(deftest ident-duplicate
  (lc/run []
    (l/store
      (lc/push (cp (l/detect :result (?< (l/flow :input)))))
      (l/spawn :main
        (l/notified :main))
      (l/transfer :main
        (l/spawned :input
          (l/notify :input))
        (l/transferred :input
          (lc/push 0))
        (l/detected :result))
      (l/check #{0})
      (l/notify :input
        (l/notified :main))
      (l/transfer :main
        (l/transferred :input
          (l/terminate :input)
          (lc/push 0))
        (l/terminated :main))
      (l/check #{0}))))

(deftest lazy-switch
  (lc/run []
    (l/store                                                ;; [{}]
      (lc/push (cp (?< (?< (l/flow :parent)))))             ;; [{} (cp)]
      (l/spawn :main
        (l/notified :main))                                 ;; [{:main cp-iterator}]
      (l/transfer :main
        (l/spawned :parent
          (l/notify :parent))
        (l/transferred :parent
          (lc/push (l/flow :child1)))
        (l/spawned :child1
          (l/notify :child1))
        (l/transferred :child1
          (lc/push 1)))
      (l/check #{1})
      (l/notify :parent
        (l/notified :main))
      (l/transfer :main
        (l/transferred :parent
          (lc/push (l/flow :child2)))
        (l/cancelled :child1
          (l/terminate :child1))
        (l/spawned :child2
          (l/notify :child2))
        (l/transferred :child2
          (lc/push 2)))
      (l/check #{2})
      (l/terminate :child2)
      (l/terminate :parent
        (l/terminated :main)))))
