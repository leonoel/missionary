(ns missionary.test.zip
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(t/deftest simple-with-cancel
  (t/is (= []
          (lc/run []
            (l/store
              (lc/push (m/zip vector (l/flow :x) (l/flow :y)))
              (l/spawn :main
                (l/spawned :x)
                (l/spawned :y))
              (l/notify :x)
              (l/notify :y
                (l/notified :main))
              (l/transfer :main
                (l/transferred :x (lc/push :x1))
                (l/transferred :y (lc/push :y1)))
              (l/check #{[:x1 :y1]})
              (l/cancel :main
                (l/cancelled :x)
                (l/cancelled :y)))))))

(t/deftest an-input-terminates
  (t/is (= []
          (lc/run []
            (l/store
              (lc/push (m/zip vector (l/flow :x) (l/flow :y)))
              (l/spawn :main
                (l/spawned :x)
                (l/spawned :y))
              (l/terminate :x
                (l/cancelled :y))
              (l/terminate :y
                (l/terminated :main))))))
  ;; this is the current behavior, which is non-compliant
  #_(t/testing "produces 1 last value after :x terminating"
    (t/is (= []
            (lc/run []
              (l/store
                (lc/push (m/zip vector (l/flow :x) (l/flow :y)))
                (l/spawn :main
                  (l/spawned :x)
                  (l/spawned :y))
                (l/notify :x)
                (l/terminate :x
                  (l/cancelled :y)
                  (l/transferred :y (lc/push :y1)))))))))

(def err (ex-info "" {}))
(def f-called (concat (l/check #{:f}) (lc/push nil)))

(t/deftest f-throws
 (t/is (= []
          (lc/run []
            (l/store
              (lc/push (m/zip (fn [_ _] (lc/event :f) (throw err)) (l/flow :x) (l/flow :y)))
              (l/spawn :main
                (l/spawned :x)
                (l/spawned :y))
              (l/notify :x)
              (l/notify :y
                (l/notified :main))
              (l/crash :main
                (l/transferred :x (lc/push :x1))
                (l/transferred :y (lc/push :y1))
                f-called
                (l/cancelled :x)
                (l/cancelled :y))
              (l/check #{err}))))))
