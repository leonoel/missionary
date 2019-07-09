(ns missionary.helpers
  (:require [missionary.core :as m]
            #?(:clj [clojure.test] :cljs [cljs.test])))

(defmacro deftest* [name & body]
  (if (:js-globals &env)
    `(cljs.test/deftest ~name (cljs.test/async done# ((m/sp ~@body) (fn [_#] (done#)) (fn [e#] (throw e#)))))
    `(clojure.test/deftest ~name (do ~@body))))

(defmacro failing? [& body]
  (if (:js-globals &env)
    `(try ~@body false (catch :default _# true))
    `(try ~@body false (catch Throwable _# true))))


