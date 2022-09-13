(ns missionary.test-dev
  (:require [kaocha runner stacktrace]))

(defn run [_opts]
  (binding [kaocha.stacktrace/*stacktrace-filters* (conj kaocha.stacktrace/*stacktrace-filters* "kaocha.")]
    (kaocha.runner/exec-fn {:kaocha/watch? true, :kaocha/fail-fast? true})))
