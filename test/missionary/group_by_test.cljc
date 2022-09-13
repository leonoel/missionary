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
              (group-transfer "a3")
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
              (group-transfer-new "b1")
              (l/cancel :a
                (l/notified :a))
              (l/crash :a
                (l/terminated :a))
              (l/check (partial instance? Cancelled))
              ;; after termination, if a new value comes in, we get a new flow
              (group-transfer-new "a2")
              (l/notify :input
                (l/transferred :input "a3")
                (l/notified :a))
              (l/cancel :a
                (l/notified :main))
              (l/crash :a
                (l/terminated :a))
              (l/check (partial instance? Cancelled))
              (l/transfer :main)
              (l/check (comp #{:a} first))
              ;; why no transfer of input here?
              (l/notify :input)
              )))))
