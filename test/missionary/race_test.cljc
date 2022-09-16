(ns missionary.race-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(def err1 (ex-info "1" {}))
(def err2 (ex-info "2" {}))
(t/deftest success
  (t/is (= []
          (lc/run
            (l/store
              (m/race (l/task :a) (l/task :b))
              (l/start :main (l/started :a) (l/started :b))
              (l/succeed :a 1
                (l/cancelled :a)
                (l/cancelled :b))
              (l/fail :b err1
                (l/succeeded :main #{1})))))))

(t/deftest fail
  (t/is (= []
          (lc/run
            (l/store
              (m/race (l/task :a) (l/task :b))
              (l/start :main (l/started :a) (l/started :b))
              (l/fail :a err1)
              (l/fail :b err2
                (l/failed :main #(= [err1 err2] (::m/errors (ex-data %))))))))))

(t/deftest cancel
  (t/is (= []
          (lc/run
            (l/store
              (m/race (l/task :a) (l/task :b))
              (l/start :main (l/started :a) (l/started :b))
              (l/cancel :main
                (l/cancelled :a)
                (l/cancelled :b)))))))
