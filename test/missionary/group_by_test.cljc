(ns missionary.group-by-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import [missionary Cancelled]))

(lc/defword init [flow]
  [flow (l/spawn :main
          (l/spawned :input))])

(lc/defword spawn-group [k & events]
  [(l/dup) (l/check (comp #{k} first)) peek (lc/call 1) (apply l/spawn k events)])

(def key-fn (comp keyword str first))

(lc/defword group-transfer-new [v]
  [(l/notify :input
     (l/transferred :input v)
     (l/notified :main))
   (l/transfer :main)
   (spawn-group (key-fn v) (l/notified (key-fn v)))
   (l/transfer (key-fn v))
   (l/check #{v})])

(lc/defword group-transfer [v]
  [(l/notify :input
     (l/transferred :input v)
     (l/notified (key-fn v)))
   (l/transfer (key-fn v))
   (l/check #{v})])

(t/deftest simple-with-cancel
  (t/is (= []
          (lc/run
            (l/store
              (init (m/group-by key-fn (l/flow :input)))
              (group-transfer-new "a1")
              (group-transfer "a2")
              (group-transfer-new "b1")
              (group-transfer "b2")
              (group-transfer "b3")
              (group-transfer "a2")
              (l/cancel :main
                (l/cancelled :input))
              (l/terminate :input
                (l/terminated :a)
                (l/terminated :b)
                (l/terminated :main)))))))

(def err (ex-info "" {}))
(lc/defword input-crash [e]
  [(l/notify :input
     (l/crashed :input e)
     (l/cancelled :input)
     (l/notified :main))
   (l/crash :main)
   (l/check #{e})])

(t/deftest input-crashes
  (t/is (= []
          (lc/run
            (l/store
              (init (m/group-by key-fn (l/flow :input)))
              (group-transfer-new "a1")
              (group-transfer-new "b1")
              (input-crash err)
              (l/terminate :input
                (l/terminated :a)
                (l/terminated :b)
                (l/terminated :main)))))))

(t/deftest group-is-cancelled
  (t/is (= []
          (lc/run
            (l/store
              (init (m/group-by key-fn (l/flow :input)))
              (group-transfer-new "a1")
              (l/notify :input
                (l/transferred :input "a2")
                (l/notified :a))
              (l/cancel :a
                ;; we haven't consumed "a2", so a new group is created
                ;; the new group will allow transferring "a2"
                (l/notified :main))
              (l/crash :a
                (l/terminated :a))
              (l/check (partial instance? Cancelled))
              ;; we don't transfer input yet, because of backpressure on "a2"
              (l/notify :input)
              (l/transfer :main)
              (spawn-group :a (l/notified :a))
              ;; we finally transfer "a2", releasing backpressure
              (l/transfer :a
                (l/transferred :input "a3")
                (l/notified :a))
              (l/check #{"a2"}))))))

(t/deftest group-collision
  (t/is (= []
          (lc/run
            (l/store
              (m/group-by identity (l/flow :input))
              (l/spawn :main (l/spawned :input))

              (l/notify :input
                (l/transferred :input "e")
                (l/notified :main))
              (l/transfer :main)
              val (lc/call 1)
              (l/spawn :group-e (l/notified :group-e))
              (l/transfer :group-e)
              (l/check #{"e"})

              (l/notify :input
                (l/transferred :input "i")
                (l/notified :main))
              (l/transfer :main)
              val (lc/call 1)
              (l/spawn :group-i (l/notified :group-i))
              (l/transfer :group-i)
              (l/check #{"i"})

              (l/notify :input
                (l/transferred :input "f")
                (l/notified :main))
              (l/transfer :main)
              val (lc/call 1)
              (l/spawn :group-f (l/notified :group-f))
              (l/transfer :group-f)
              (l/check #{"f"})

              (l/cancel :group-e
                (l/notified :group-e))

              (l/notify :input
                (l/transferred :input "f")
                (l/notified :group-f)))))))