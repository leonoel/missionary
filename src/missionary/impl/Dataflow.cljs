(ns missionary.impl.Dataflow
  (:import missionary.Cancelled))

(defn nop [])
(defn send-rf [x !] (! x) x)

(deftype Port [^:mutable bound
               ^:mutable value
               ^:mutable watch]
  IFn
  (-invoke [_ t]
    (when-not bound
      (set! bound true)
      (set! value t)
      (reduce send-rf t (persistent! watch))
      (set! watch nil)) value)
  (-invoke [_ s! f!]
    (if bound
      (do (s! value) nop)
      (let [! #(s! %)]
        (set! watch (conj! watch !))
        #(when-not bound
           (when (contains? watch !)
             (set! watch (disj! watch !))
             (f! (Cancelled. "Dataflow variable dereference cancelled."))))))))

(defn make [] (->Port false nil (transient #{})))
