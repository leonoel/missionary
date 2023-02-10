(ns missionary.impl.Mailbox
  (:import missionary.Cancelled))

(defn nop [])

(deftype Port [^:mutable enqueue
               ^:mutable dequeue
               ^:mutable readers]
  IFn
  (-invoke [_ t]
    (if-some [[!] (seq readers)]
      (do (set! readers (disj readers !)) (! t))
      (do (.push enqueue t) nil)))
  (-invoke [_ s! f!]
    (if (zero? (alength dequeue))
      (if (zero? (alength enqueue))
        (let [! #(s! %)]
          (set! readers (conj readers !))
          #(when (contains? readers !)
             (set! readers (disj readers !))
             (f! (Cancelled. "Mailbox fetch cancelled."))))
        (let [tmp enqueue]
          (set! enqueue dequeue)
          (set! dequeue (.reverse tmp))
          (s! (.pop tmp)) nop))
      (do (s! (.pop dequeue)) nop))))

(defn make [] (->Port (array) (array) #{}))
