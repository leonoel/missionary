(ns missionary.ap-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import [missionary Cancelled]))

;; amb
;; amb=

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

(t/deftest ?>
  (t/is (= []
          (lc/run
            (l/store
              (m/ap (m/?> (l/flow :input)))
              (l/spawn :main (l/spawned :input))
              (l/notify :input
                (l/transferred :input 1)
                (l/notified :main))
              (l/notify :input)
              (l/transfer :main
                (l/transferred :input 2)
                (l/notified :main))
              (l/check #{1})
              (l/transfer :main)
              (l/check #{2})
              (l/terminate :input (l/terminated :main)))))))

(t/deftest ?>-cancel-with-crash
  (t/is (= []
          (lc/run
            (l/store
              (m/ap (m/?> (l/flow :input)))
              (l/spawn :main (l/spawned :input))
              (l/cancel :main (l/cancelled :input))
              (l/notify :input
                (l/crashed :input err)
                (l/notified :main))
              (l/crash :main)
              (l/check #{err})
              (l/terminate :input (l/terminated :main)))))))

(t/deftest ?>-parallel
  (t/is (= []
          (lc/run
            (l/store
              (m/ap (m/?> 3 (l/flow :input)))
              (l/spawn :main (l/spawned :input))
              (l/notify :input
                (l/transferred :input 1)
                (l/notified :main))
              (l/notify :input (l/transferred :input 2))
              (l/notify :input (l/transferred :input 3))
              (l/notify :input)
              (l/transfer= 1 :main
                (l/transferred :input 4)
                (l/notified :main))
              (l/transfer= 2 :main (l/notified :main))
              (l/transfer= 3 :main (l/notified :main))
              (l/transfer= 4 :main)
              (l/terminate :input (l/terminated :main)))))))
