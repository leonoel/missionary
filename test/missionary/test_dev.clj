(ns missionary.test-dev
  (:require pjstadig.humane-test-output
            io.aviso.repl
            kaocha.repl
            kaocha.watch
            kaocha.runner
            kaocha.stacktrace))
(defn run [_opts]
  (pjstadig.humane-test-output/activate!)
  ;; (alter-var-root #'kaocha.stacktrace/print-stack-trace (constantly io.aviso.repl/pretty-print-stack-trace))
  ;; (alter-var-root #'kaocha.stacktrace/print-cause-trace (constantly io.aviso.repl/pretty-print-stack-trace))
  (binding [kaocha.stacktrace/*stacktrace-filters* (conj kaocha.stacktrace/*stacktrace-filters* "kaocha.")]
    (kaocha.runner/exec-fn {:kaocha/watch? true, :kaocha/fail-fast? true})))
