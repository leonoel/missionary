(ns missionary.pairing-heap-test-impl
  (:require [missionary.impl.pairing-heap :as ph]))

(deftype HeapState [head tail])
(deftype Node [id ^:unsynchronized-mutable child ^:unsynchronized-mutable sibling])
(deftype Heap [^:unsynchronized-mutable state ready?])

(defn id [^Node n]
  (.-id n))

(defn get-ready-atom [^Heap h] (.-ready? h))

(defn set-ready! [h] (reset! (get-ready-atom h) true))

(defmacro lt [x y]
  `(< (id ~x) (id ~y)))

(defmacro mk-heap-state [h t] `(->HeapState ~h ~t))

(ph/defimpl impl
  :lt       lt
  :ready    set-ready!
  :mk-state mk-heap-state
  :state    Heap/state
  :head     HeapState/head
  :tail     HeapState/tail
  :child    Node/child
  :sibling  Node/sibling)

(defn heap []
  (let [h (->Heap nil (atom false))]
    (impl ph/init h) h))

(defn insert-node [h id]
  (impl ph/insert h
    (->Node id nil nil)) h)

(defn dequeue-all [rf r n]
  (loop [n n
         r (rf r (id n))]
    (if (reduced? r)
      @r (if-some [n (impl ph/dequeue3 n)]
           (recur n (rf r (id n))) r))))

(defn accept [h] (impl ph/accept h))

(defn accept-as-vec [h] (dequeue-all conj [] (accept h)))
