(ns missionary.test.watch
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import [missionary Cancelled]))

(t/deftest watch
  (let [!a (atom 0)]
    (t/is (= []
            (lc/run
              (l/store
                (m/watch !a)
                (l/spawn :main
                  (l/notified :main))
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
                (m/watch !a)
                (l/spawn :main
                  (l/notified :main))
                (l/cancel :main)
                (l/crash :main
                  (l/terminated :main))
                (l/check #(instance? Cancelled %))))))))
