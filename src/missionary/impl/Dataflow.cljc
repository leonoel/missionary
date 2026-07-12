(ns missionary.impl.Dataflow
  #?(:cljs (:import missionary.Cancelled)
     :cljd (:require [missionary.Cancelled :refer [Cancelled]])))

(defn nop [])
(defn send-rf [x !] (! x) x)

(declare cancel-watch)

(deftype Port [^:mutable bound
               ^:mutable value
               ^:mutable watch]
  #?(:cljs IFn :cljd cljd.core/IFn)
  (-invoke [_ t]
    (when-not bound
      (set! bound true)
      (set! value t)
      (reduce send-rf t (persistent! watch))
      (set! watch nil)) value)
  (-invoke [this s! f!]
    (if bound
      (do (s! value) nop)
      (let [! #(s! %)]
        (set! watch (conj! watch !))
        #(cancel-watch this ! f!)))))

(defn cancel-watch [^Port p ! f!]
  (when-not (.-bound p)
    (when (contains? (.-watch p) !)
      (set! (.-watch p) (disj! (.-watch p) !))
      (f! (Cancelled. "Dataflow variable dereference cancelled.")))))

(defn make [] (->Port false nil (transient #{})))
