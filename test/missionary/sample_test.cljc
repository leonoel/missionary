(ns missionary.sample-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(lc/defword sample-init [flow]
  [flow
   (l/spawn :main
     (l/spawned :x (l/notify :x))
     (l/spawned :y (l/notify :y))
     (l/spawned :sampler))
   (l/notify :sampler
     (l/notified :main))])

(t/deftest simple-with-cancel
  (t/is (= []
          (lc/run
            (l/store
              (sample-init (m/sample vector (l/flow :x) (l/flow :y) (l/flow :sampler)))
              (l/transfer :main
                (l/transferred :x :x1)
                (l/transferred :y :y1)
                (l/transferred :sampler :sampler1))
              (l/check #{[:x1 :y1 :sampler1]})
              (l/cancel :main
                (l/cancelled :sampler))
              (l/terminate :sampler
                (l/cancelled :x)
                (l/cancelled :y)))))))

(t/deftest consecutive-notify-causes-retransfer
  (t/is (= []
          (lc/run
            (l/store
              (sample-init (m/sample vector (l/flow :x) (l/flow :y) (l/flow :sampler)))
              (l/transfer :main
                (l/transferred :x (l/compose (l/notify :x) :x1))
                (l/transferred :x :x2)
                (l/transferred :y :y1)
                (l/transferred :sampler :sampler1))
              (l/check #{[:x2 :y1 :sampler1]})
              (l/cancel :main
                (l/cancelled :sampler))
              (l/terminate :sampler
                (l/cancelled :x)
                (l/cancelled :y)))))))

(def err (ex-info "" {}))
(t/deftest input-crashes
  (t/is (= []
          (lc/run
            (l/store
              (sample-init (m/sample vector (l/flow :x) (l/flow :y) (l/flow :sampler)))
              (l/crash :main
                (l/crashed :x err)
                (l/transferred :sampler :sampler1)
                (l/cancelled :sampler))
              (l/check #{err})
              (l/terminate :sampler
                (l/cancelled :x)
                (l/cancelled :y)
                (l/transferred :y :y1)))))))

(def f-called (l/compose (l/check #{:f}) nil))

(t/deftest f-crashes
  (t/is (= []
          (lc/run
            (l/store
              (sample-init (m/sample (fn [& _] (lc/event :f) (throw err)) (l/flow :x) (l/flow :y) (l/flow :sampler)))
              (l/crash :main
                (l/transferred :x :x1)
                (l/transferred :y :y1)
                (l/transferred :sampler :sampler1)
                f-called
                (l/cancelled :sampler))
              (l/check #{err})
              (l/terminate :sampler
                (l/cancelled :x)
                (l/cancelled :y)))))))

(t/deftest input-terminates
  (t/is (= []
          (lc/run
            (l/store
              (sample-init (m/sample vector (l/flow :x) (l/flow :y) (l/flow :sampler)))
              (l/transfer :main
                (l/transferred :x :x1)
                (l/transferred :y :y1)
                (l/transferred :sampler :sampler1))
              (l/check #{[:x1 :y1 :sampler1]})
              (l/terminate :x)
              (l/terminate :y)
              (l/terminate :sampler
                (l/cancelled :x)
                (l/cancelled :y)
                (l/terminated :main)))))))
