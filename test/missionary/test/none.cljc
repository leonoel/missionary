(ns missionary.test.none
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(t/deftest none
  (t/is (= []
          (lc/run
            (l/store
              m/none
              (l/spawn :main
                (l/terminated :main))
              (l/cancel :main))))))
