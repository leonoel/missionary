(ns missionary.observe-test
  (:require [lolcat.core :as lc]
            [lolcat.lib :as l]
            [missionary.core :as m]
            [clojure.test :as t])
  (:import [missionary Cancelled]))

(t/deftest a-subject-that-is-immediately-ready
  (t/is (= []
          (lc/run
            (l/store
              (m/observe (fn [f] (f nil) #(do)))
              (l/spawn :main
                (l/notified :main))
              (l/transfer :main)
              (l/check nil?)
              (l/cancel :main
                (l/notified :main))
              (l/crash :main
                (l/terminated :main))
              (l/check #(instance? Cancelled %)))))))

(defn interrupt-current! []
  #?(:clj (.interrupt (Thread/currentThread))))

(t/deftest overflow
  (t/is (= []
          (lc/run
            (l/store
              (m/observe lc/event)
              (l/spawn :main
                (l/compose
                  (l/insert :f)
                  #(do)))
              (l/signal :f :x (l/notified :main))
              interrupt-current!
              (lc/call 0)
              (lc/drop 0)
              (l/signal-error :f :x))))))

;; unsubscribe fn is called after cancellation
;; we don't know when exactly should the unsubscription happen
(t/deftest cancellation
  (t/is (= []
          (lc/run
            (l/store
              (m/observe lc/event)
              (l/spawn :main
                (l/compose
                  (lc/drop 0)
                  #(lc/event :unsub)))
              (l/cancel :main
                (l/compose
                  (l/check #{:unsub})
                  nil)
                (l/notified :main))
              (l/crash :main
                (l/terminated :main))
              (l/check #(instance? Cancelled %)))))))

;; subject throws exception after callback
(t/deftest subject-callback-throw
  (t/is (= []
          (lc/run
            (l/store
              (m/observe (fn [!] (! :foo) (assert false)))
              (l/spawn :main
                (l/notified :main))
              (l/crash :main
                (l/terminated :main))
              (l/check #(instance? #?(:clj Error :cljs js/Error) %)))))))