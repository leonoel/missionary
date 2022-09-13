(ns missionary.watch-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import [missionary Cancelled]))

(lc/defword init [flow] [flow (l/spawn :main (l/notified :main))])

(t/deftest watch
  (let [!a (atom 0)]
    (t/is (= []
            (lc/run
              (l/store
                (init (m/watch !a))
                (l/transfer :main)
                (l/check #{0})
                #(swap! !a inc)
                (lc/call 0
                  (l/notified :main))
                (lc/drop 0)
                (l/transfer :main)
                (l/check #{1})
                (l/cancel :main
                  (l/notified :main))
                (l/crash :main
                  (l/terminated :main))
                (l/check #(instance? Cancelled %))))))))

(t/deftest cancel-before-transfer
  (let [!a (atom 0)]
    (t/is (= []
            (lc/run
              (l/store
                (init (m/watch !a))
                (l/cancel :main)
                (l/crash :main
                  (l/terminated :main))
                (l/check #(instance? Cancelled %))))))))
