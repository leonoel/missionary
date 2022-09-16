(ns missionary.join-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(t/deftest success
  (t/is (= []
          (lc/run
            (l/store
              (m/join vector (l/task :a) (l/task :b))
              (l/start :main (l/started :a) (l/started :b))
              (l/succeed :a 1)
              (l/succeed :b 2
                (l/succeeded :main #{[1 2]})))))))

(def err1 (ex-info "1" {}))
(def err2 (ex-info "2" {}))
(t/deftest a-fails
  (t/is (= []
          (lc/run
            (l/store
              (m/join vector (l/task :a) (l/task :b))
              (l/start :main (l/started :a) (l/started :b))
              (l/fail :a err1
                (l/cancelled :a)
                (l/cancelled :b))
              (l/fail :b err2
                (l/failed :main #{err1})))))))

(t/deftest b-fails-after-a-succeeded
  (t/is (= []
          (lc/run
            (l/store
              (m/join vector (l/task :a) (l/task :b))
              (l/start :main (l/started :a) (l/started :b))
              (l/succeed :a 1)
              (l/fail :b err2
                (l/cancelled :a)
                (l/cancelled :b)
                (l/failed :main #{err2})))))))
(t/deftest f-throws
  (t/is (= []
          (lc/run
            (l/store
              (m/join (fn [_ _] (throw err1)) (l/task :a) (l/task :b))
              (l/start :main (l/started :a) (l/started :b))
              (l/succeed :a 1)
              (l/succeed :b 2
                (l/failed :main #{err1})))))))

(t/deftest cancel
  (t/is (= []
          (lc/run
            (l/store
              (m/join vector (l/task :a) (l/task :b))
              (l/start :main (l/started :a) (l/started :b))
              (l/cancel :main
                (l/cancelled :a)
                (l/cancelled :b))
              (l/fail :a err1
                (l/cancelled :a)
                (l/cancelled :b))
              (l/fail :b err2
                (l/failed :main #{err1})))))))
