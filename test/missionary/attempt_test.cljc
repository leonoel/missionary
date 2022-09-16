(ns missionary.attempt-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  #?(:clj (:import [clojure.lang ExceptionInfo])))

(t/deftest success
  (t/is (= []
          (lc/run
            (l/store
              (m/attempt (l/task :input))
              (l/start :main (l/started :input))
              (l/succeed :input 1
                (l/succeeded :main #(= 1 (%)))))))))

(def err (ex-info "" {}))
(t/deftest failure
  (t/is (= []
          (lc/run
            (l/store
              (m/attempt (l/task :input))
              (l/start :main (l/started :input))
              (l/fail :input err
                (l/succeeded :main #(= err (try (%) (catch ExceptionInfo e e))))))))))

(t/deftest cancel
  (t/is (= []
          (lc/run
            (l/store
              (m/attempt (l/task :input))
              (l/start :main (l/started :input))
              (l/cancel :main
                (l/cancelled :input)))))))
