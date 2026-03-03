(ns missionary.pairing-heap-test-impl
  (:require [missionary.impl.pairing-heap :as ph]))

(deftype Node [id ^:unsynchronized-mutable tail ^:unsynchronized-mutable child ^:unsynchronized-mutable sibling])
(deftype Heap [^:unsynchronized-mutable head ready?])

(defn id [^Node n]
  (.-id n))

(defn get-ready-atom [^Heap h] (.-ready? h))

(defn set-ready! [h] (reset! (get-ready-atom h) true))

(defmacro lt [x y]
  `(< (id ~x) (id ~y)))

(ph/defimpl impl
  :lt       lt
  :ready    set-ready!
  :head     Heap/head
  :tail     Node/tail
  :child    Node/child
  :sibling  Node/sibling)

(defn heap []
  (->Heap nil (atom false)))

(defn insert-node [h id]
  (impl ph/insert h
    (->Node id nil nil nil)) h)

(defn dequeue-all [rf r n]
  (loop [n n
         r r]
    (if (nil? n)
      r (let [r (rf r (id n))]
          (if (reduced? r)
            @r (recur (impl ph/dequeue n) r))))))

(defn accept [h] (impl ph/accept h))

(defn accept-as-vec [h] (dequeue-all conj [] (accept h)))
