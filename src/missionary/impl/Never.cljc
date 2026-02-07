(ns missionary.impl.Never
  #?(:cljs (:import missionary.Cancelled)
     :cljd (:require [missionary.Cancelled :refer [Cancelled]])))

(declare cancel)
(deftype Process [f ^:mutable alive]
  #?(:cljs IFn :cljd cljd.core/IFn)
  (-invoke [ps] (cancel ps)))

(defn cancel [^Process ps]
  (when (.-alive ps)
    (set! (.-alive ps) false)
    ((.-f ps) (Cancelled. "Never cancelled."))))

(defn run [f] (->Process f true))
