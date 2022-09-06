(ns missionary.test.observe
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import [missionary Cancelled]))

(t/deftest a-subject-that-is-immediately-ready
  (t/is (= []
          (lc/run []
            (l/store
              (lc/push (m/observe (fn [f] (f nil) #(do))))
              (l/spawn :main
                (l/notified :main))
              (l/transfer :main)
              (l/check nil?)
              (l/cancel :main
                (l/notified :main))
              (l/crash :main
                (l/terminated :main))
              (l/check #(instance? Cancelled %)))))))
