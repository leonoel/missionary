(ns missionary.impl.Semaphore
  (:import missionary.Cancelled))

(defn nop [])

(deftype Port [^:mutable available
               ^:mutable readers]
  IFn
  (-invoke [_]
    (if-some [[!] (seq readers)]
      (do (set! readers (disj readers !)) (!))
      (do (set! available (inc available)) nil)))
  (-invoke [_ s! f!]
    (if (zero? available)
      (let [! #(s! nil)]
        (set! readers (conj readers !))
        #(when (contains? readers !)
           (set! readers (disj readers !))
           (f! (Cancelled. "Semaphore acquire cancelled."))))
      (do (set! available (dec available))
          (s! nil) nop))))

(defn make [n] (->Port n #{}))