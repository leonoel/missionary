(ns missionary.test.seed
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import [missionary Cancelled]))

(t/deftest seed
  (t/is (= []
           (lc/run []
             (l/store
              (lc/push (m/seed [1 2 3]))
              (l/spawn :main
                       (l/notified :main))
              (l/transfer :main
                          (l/notified :main))
              (l/check #{1})
              (l/cancel :main)
              (l/crash :main
                       (l/terminated :main))
              (l/check #(instance? Cancelled %)))))))
