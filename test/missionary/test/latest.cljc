(ns missionary.test.latest
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t]))

(defn latest-init [flow]
  (concat
    (lc/push flow)
    (l/spawn :main
      (l/spawned :x (l/notify :x))
      (l/spawned :y (l/notify :y))
      (l/notified :main))))

(t/deftest simple-with-cancel
  (t/is (= []
          (lc/run []
            (l/store
              (latest-init (m/latest vector (l/flow :x) (l/flow :y)))
              (l/transfer :main
                (l/transferred :x (lc/push :x1))
                (l/transferred :y (lc/push :y1)))
              (l/check #{[:x1 :y1]})
              (l/cancel :main
                (l/cancelled :x)
                (l/cancelled :y)))))))

(t/deftest latest-is-retained
  (t/is (= []
          (lc/run []
            (l/store
              (latest-init (m/latest vector (l/flow :x) (l/flow :y)))
              (l/transfer :main
                (l/transferred :x (lc/push :x1))
                (l/transferred :y (lc/push :y1)))
              (l/check #{[:x1 :y1]})
              (l/notify :x
                (l/notified :main))
              (l/transfer :main
                (l/transferred :x (lc/push :x2)))
              (l/check #{[:x2 :y1]}))))))

(comment
  (defn- -ps [] (println :lolcat.stack (:stack (lc/context identity))))
  (def ps (concat (lc/push -ps) (lc/call 0) (lc/drop 0)))
  (defn- ->r [v] (lc/context update :words (fnil conj []) v))
  (defn- r-> [] (lc/context update :words pop))
  (def >r (concat (lc/push ->r) (lc/call 1) (lc/drop 0) (lc/drop 0)))
  (def r> (concat (lc/push r->) (lc/call 0))))

(t/deftest consecutive-notify-causes-retransfer
  (t/is (= []
          (lc/run []
            (l/store
              (latest-init (m/latest vector (l/flow :x) (l/flow :y)))
              (l/transfer :main
                (l/transferred :x (l/notify :x) (lc/push :x1))
                (l/transferred :x (lc/push :x2))
                (l/transferred :y (lc/push :y1)))
              (l/check #{[:x2 :y1]}))))))

(def err (ex-info "" {}))

(t/deftest input-crashes
  (t/is (= []
          (lc/run []
            (l/store
              (latest-init (m/latest vector (l/flow :x) (l/flow :y)))
              (l/crash :main
                (l/crashed :x (lc/push err))
                (l/cancelled :x)
                (l/cancelled :y)
                (l/transferred :y (lc/push :y1)))
              (l/check #{err}))))))

(def f-called (concat (l/check #{:f}) (lc/push nil)))

(t/deftest f-crashes
  (t/is (= []
          (lc/run []
            (l/store
              (latest-init (m/latest (fn [& _] (lc/event :f) (throw err)) (l/flow :x) (l/flow :y)))
              (l/crash :main
                (l/transferred :x (lc/push :x1))
                (l/transferred :y (lc/push :y1))
                f-called
                (l/cancelled :x)
                (l/cancelled :y))
              (l/check #{err}))))))

(t/deftest input-terminates
  (t/is (= []
          (lc/run []
            (l/store
              (latest-init (m/latest vector (l/flow :x) (l/flow :y)))
              (l/transfer :main
                (l/transferred :x (lc/push :x1))
                (l/transferred :y (lc/push :y1)))
              (l/check #{[:x1 :y1]})
              (l/terminate :x)
              (l/terminate :y
                (l/terminated :main)))))))
