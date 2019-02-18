(ns missionary.core-test
  (:require
    [missionary.core :as m :refer [?]]
    [missionary.misc :refer [nop]]
    [cloroutine.impl :refer [safe]]
    [clojure.test :as t :refer [deftest is]])
  #?(:cljs (:require-macros
             [missionary.core-test :refer [deftest-async]]
             [cljs.test :refer [async]])))

(defmacro deftest-async [name & body]
  (if (:js-globals &env)
    `(deftest ~name (t/async done# ((m/sp ~@body) (fn [_#] (done#)) (fn [e#] (throw e#)))))
    `(deftest ~name ~@body)))

(def success (fn [! _] (! true) nop))
(def failure (fn [_ !] (! (ex-info "" {})) nop))

(deftest-async timing
  (is (? (m/sleep 0 true)))
  (is (safe [_ (? (m/timeout 0 (m/sleep 10 false)))] true)))

(deftest-async parallel
  (is (= [] (? (m/join))))
  (is (= [true] (? (m/join success))))
  (is (= [true true true] (? (m/join success success success))))
  (is (safe [_ (? (m/join success success failure))] true))
  (is (safe [_ (? (m/race))] true))
  (is (? (m/race success)))
  (is (? (m/race success failure failure)))
  (is (safe [_ (? (m/race failure failure failure))] true)))

(deftest-async sequential
  (is (? (m/sp true)))
  (is (? (m/sp (let [x (? success)] x))))
  (is (? (m/sp (safe [_ (? failure)] true)))))

(deftest-async synchronization
  (is (let [sem (m/sem 7)]
        (safe [_ (? (->> (m/sp (while true
                                 (m/holding sem (? (m/sleep 0)))))
                         (repeat 100)
                         (apply m/join)
                         (m/timeout 100)))]
          (do (dotimes [_ 7] (? sem)) true))))
  (is (let [rdv (m/rdv)]
        (safe [_ (? (->> (m/sp (while true
                                 (? (m/compel (m/join rdv (rdv false))))
                                 (? (m/sleep 0))))
                         (repeat 100)
                         (apply m/join)
                         (m/timeout 100)))]
          (safe [_ (? (m/timeout 0 rdv))] true))))
  (is (let [buf (m/buf)]
        (safe [_ (? (->> (m/sp (while true
                                 (buf false)
                                 (? (m/compel buf))
                                 (? (m/sleep 0))))
                         (repeat 100)
                         (apply m/join)
                         (m/timeout 100)))]
          (safe [_ (? (m/timeout 0 buf))] true))))
  (is (let [dfv (m/dfv)] (dfv true) (? dfv))))