(ns missionary.impl.Sleep
  #?(:cljs (:import missionary.Cancelled)
     :cljd (:require [missionary.Cancelled :refer [Cancelled]]
                     ["dart:async" :as dart:async])))

(declare cancel)
(deftype Process
  [failure ^:mutable handler
   ^:mutable pending]
  #?(:cljs IFn :cljd cljd.core/IFn)
  (-invoke [s] (cancel s)))

(defn cancel [^Process s]
  (when (.-pending s)
    (set! (.-pending s) false)
    #?(:cljs (js/clearTimeout (.-handler s))
       :cljd (.cancel (.-handler s)))
    ((.-failure s) (Cancelled. "Sleep cancelled."))))

(defn run [d x s f]
  (let [slp (->Process f nil true)]
    (set! (.-handler slp)
      #?(:cljs (js/setTimeout #(do (set! (.-pending slp) false) (s x)) d)
         :cljd (dart:async/Timer (Duration. :milliseconds d)
                                 (fn [] (set! (.-pending slp) false) (s x)))))
    slp))
