(ns missionary.zip-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(lc/defword init [flow] [flow (l/spawn :main (l/spawned :x) (l/spawned :y))])

(t/deftest simple-with-cancel
  (t/is (= []
          (lc/run
            (l/store
              (init (m/zip vector (l/flow :x) (l/flow :y)))
              (l/notify :x)
              (l/notify :y
                (l/notified :main))
              (l/transfer :main
                (l/transferred :x :x1)
                (l/transferred :y :y1))
              (l/check #{[:x1 :y1]})
              (l/cancel :main
                (l/cancelled :x)
                (l/cancelled :y)))))))

(t/deftest an-input-terminates
  (t/is (= []
          (lc/run
            (l/store
              (init (m/zip vector (l/flow :x) (l/flow :y)))
              (l/terminate :x
                (l/cancelled :y))
              (l/terminate :y
                (l/terminated :main)))))))

(def err (ex-info "" {}))
(def f-called (l/compose (l/check #{:f}) nil))

(t/deftest f-throws
 (t/is (= []
         (lc/run
           (l/store
             (init (m/zip (fn [_ _] (lc/event :f) (throw err)) (l/flow :x) (l/flow :y)))
             (l/notify :x)
             (l/notify :y
               (l/notified :main))
             (l/crash :main
               (l/transferred :x :x1)
               (l/transferred :y :y1)
               f-called
               (l/cancelled :x)
               (l/cancelled :y))
             (l/check #{err}))))))

(t/deftest doesnt-overconsume
  (t/is (= []
          (lc/run
            (l/store
              (init (m/zip vector (l/flow :x) (l/flow :y)))
              (l/notify :x)
              (l/notify :y
                (l/notified :main))
              (l/transfer :main
                (l/transferred :x (l/terminate :x) :x1)
                (l/transferred :y (l/notify :y) :y1)
                ;; the next transfer shouldn't happen
                ;; zip already has all the data it needs and won't produce more since `:x` terminated
                (l/transferred :y :y2)
                (l/cancelled :y))
              (l/check #{[:x1 :y1]}))))))
