(ns missionary.test.observe
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import [missionary Cancelled]))

(t/deftest a-subject-that-is-immediately-ready
  (t/is (= []
          (lc/run []
            (l/store
              (lc/push (m/observe (fn [f] (f nil) #(do))))
              (l/spawn :main
                (l/notified :main))
              (l/transfer :main)
              (l/check nil?)
              (l/cancel :main
                (l/notified :main))
              (l/crash :main
                (l/terminated :main))
              (l/check #(instance? Cancelled %)))))))

(t/deftest overflow
  (t/is (= []
           (lc/run []
             (l/store
              (lc/push (m/observe lc/event))
              (l/spawn :main
                       (concat
                        (l/insert :f)
                        (lc/push #(do))))
              (l/signal :f :x (l/notified :main))
              (l/signal-error :f :x))))))

;; unsubscribe fn is called after cancellation
;; we don't know when exactly should the unsubscription happen
(t/deftest cancellation
  (t/is (= []
           (lc/run []
             (l/store
              (lc/push (m/observe lc/event))
              (l/spawn :main
                       (concat
                        (lc/drop 0)
                        (lc/push #(lc/event :unsub))))
              (l/cancel :main
                        (l/notified :main))
              (l/crash :main
                       (l/terminated :main)
                       (concat
                        (l/check #{:unsub})
                        (lc/push nil)))
              (l/check #(instance? Cancelled %)))))))
