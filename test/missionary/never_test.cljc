(ns missionary.never-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import [missionary Cancelled]))

(t/deftest simple
  (t/is (= []
          (lc/run
            (l/store
              m/never
              (l/start :main)
              (l/cancel :main
                (l/failed :main (partial instance? Cancelled))))))))
