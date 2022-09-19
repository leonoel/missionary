(ns missionary.ap-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import [missionary Cancelled]))

;; ?>
;; ?=
;; amb=
;; amb>
;; cancellation

(t/deftest ?
  (t/is (= []
          (lc/run
            (l/store
              (m/ap (m/? (l/task :input)))
              (l/spawn :main (l/started :input))
              (l/succeed :input 1 (l/notified :main))
              (l/transfer :main (l/terminated :main))
              (l/check #{1}))))))

(def err (ex-info "" {}))
(t/deftest ?-cancel-with-failure
  (t/is (= []
          (lc/run
            (l/store
              (m/ap (m/? (l/task :input)))
              (l/spawn :main (l/started :input))
              (l/cancel :main (l/cancelled :input))
              (l/fail :input err (l/notified :main))
              (l/crash :main (l/terminated :main))
              (l/check #{err}))))))
