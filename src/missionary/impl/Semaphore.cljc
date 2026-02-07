(ns missionary.impl.Semaphore
  #?(:cljs (:import missionary.Cancelled)
     :cljd (:require [missionary.Cancelled :refer [Cancelled]])))

(defn nop [])

(declare cancel-acquire)

(comment
  (require '[cljs.repl.node :as node])                                                                 
  (require '[cider.piggieback :as piggieback])                                                         
  (piggieback/cljs-repl (node/repl-env)))
  
  
  
  

(deftype Port [^:mutable available
               ^:mutable readers]
  #?(:cljs IFn :cljd cljd.core/IFn)
  ;; Release - no args
  (-invoke [_]
    (if-some [[!] (seq readers)]
      (do (set! readers (disj readers !)) (!))
      (do (set! available (inc available)) nil)))
  ;; Acquire - with success/failure callbacks
  (-invoke [this s! f!]
    (if (zero? available)
      (let [! #(s! nil)]
        (set! readers (conj readers !))
        #(cancel-acquire this ! f!))
      (do (set! available (dec available))
          (s! nil) nop))))

(defn cancel-acquire [^Port p ! f!]
  (let [rs (.-readers p)]
    (when (contains? rs !)
      (set! (.-readers p) (disj rs !))
      (f! (Cancelled. "Semaphore acquire cancelled.")))))

(defn make [n] (->Port n #{}))
