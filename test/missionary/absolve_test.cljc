(ns missionary.absolve-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(t/deftest simple
  (t/is (= []
          (lc/run
            (l/store
              (m/absolve (l/task :input))
              (l/start :main (l/started :input))
              (l/succeed :input #(do 1)
                (l/succeeded :main #{1})))))))

(t/deftest input-fails
  (t/is (= []
          (lc/run
            (l/store
              (m/absolve (l/task :input))
              (l/start :main (l/started :input))
              (l/fail :input 2
                (l/failed :main #{2})))))))

(t/deftest cancel
  (t/is (= []
          (lc/run
            (l/store
              (m/absolve (l/task :input))
              (l/start :main (l/started :input))
              (l/cancel :main
                (l/cancelled :input)))))))
