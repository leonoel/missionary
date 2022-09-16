(ns missionary.sp-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(t/deftest success
  (t/is (= []
          (lc/run
            (l/store
              (m/sp
                (l/detect :first 1)
                (let [v (m/? (l/task :input))]
                  (l/detect :second 2)
                  v))
              (l/start :main
                (l/detected :first)
                (l/started :input))
              (l/succeed :input 2
                (l/detected :second)
                (l/succeeded :main #{2})))))))

(def err (ex-info "" {}))
(t/deftest failure
  (t/is (= []
          (lc/run
            (l/store
              (m/sp
                (l/detect :first 1)
                (let [v (m/? (l/task :input))]
                  (l/detect :second 2)
                  v))
              (l/start :main
                (l/detected :first)
                (l/started :input))
              (l/fail :input err
                (l/failed :main #{err})))))))
