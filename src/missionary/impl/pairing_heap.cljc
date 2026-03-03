(ns missionary.impl.pairing-heap
  (:require [missionary.impl.varhandle :as v]))

(defmacro accept [{:keys [head tail child]} o]
  `(let [o# ~o]
     (loop []
       (when-some [h# (v/get-volatile ~head o#)]
         (if-some [t# (v/get-volatile ~tail h#)]
           (if (v/compare-and-set ~tail h# t# nil)
             (do (v/set-volatile ~head o# nil)
                 (when-not (identical? h# t#)
                   (v/set ~child h# t#)) h#)
             (recur))
           (recur))))))

(defmacro insert [{:keys [lt ready head tail child sibling]} o x]
  `(let [o# ~o
         x# ~x]
     (loop []
       (if-some [h# (v/get-volatile ~head o#)]
         (if-some [t# (v/get-volatile ~tail h#)]
           (if (~lt x# h#)
             (if (v/compare-and-set ~tail h# t# nil)
               (do (when-not (identical? h# t#)
                     (v/set ~child h# t#))
                   (v/set ~tail x# h#)
                   (v/set-volatile ~head o# x#))
               (recur))
             (do (when-not (identical? h# t#)
                   (v/set ~sibling x# t#))
                 (when-not (v/compare-and-set ~tail h# t# x#)
                   (v/set ~sibling x# nil)
                   (recur))))
           (recur))
         (do (v/set ~tail x# x#)
             (if (v/compare-and-set ~head o# nil x#)
               (~ready o#)
               (do (v/set ~tail x# nil)
                   (recur))))))))

(defmacro get-and-set [f x y]
  `(let [x# ~x
         y# ~y
         z# (v/get ~f x#)]
     (v/set ~f x# y#) z#))

(defmacro meld [{:keys [lt child sibling]} x y]
  `(let [x# ~x
         y# ~y]
     (if (~lt x# y#)
       (do (v/set ~sibling y# (v/get ~child x#))
           (v/set ~child x# y#) x#)
       (do (v/set ~sibling x# (v/get ~child y#))
           (v/set ~child y# x#) y#))))

(defmacro dequeue [{:keys [child sibling] :as impl} h]
  `(when-some [x# (get-and-set ~child ~h nil)]
     (if-some [y# (get-and-set ~sibling x# nil)]
       (loop [z# (get-and-set ~sibling y# nil)
              h# (meld ~impl x# y#)]
         (if (nil? z#)
           h# (if-some [y# (get-and-set ~sibling z# nil)]
                (recur (get-and-set ~sibling y# nil)
                  (meld ~impl h# (meld ~impl z# y#)))
                (meld ~impl h# z#)))) x#)))

(defmacro defimpl [sym & {:as impl}]
  (let [f (gensym)
        args (gensym)]
    `(defmacro ~sym [~f & ~args]
       (cons ~f (cons ~(list `quote impl) ~args)))))

(comment
  (deftype Node [id ^:unsynchronized-mutable tail ^:unsynchronized-mutable child ^:unsynchronized-mutable sibling])
  (deftype Heap [^:unsynchronized-mutable head])

  (defn id [^Node n]
    (.-id n))

  (defmacro ready [_]
    `(prn :ready))

  (defmacro lt [x y]
    `(< (id ~x) (id ~y)))

  (defimpl impl
    :lt       lt
    :ready    ready
    :head     Heap/head
    :tail     Node/tail
    :child    Node/child
    :sibling  Node/sibling)

  (defn heap []
    (->Heap nil))

  (defn insert-node [h id]
    (impl insert h
      (->Node id nil nil nil)) h)

  (defn insert-n [h n]
    (reduce insert-node h
      (shuffle (range n))))

  (defn dequeue-all [rf r n]
    (loop [n n
           r (rf r (id n))]
      (if (reduced? r)
        @r (if-some [n (impl dequeue n)]
             (recur n (rf r (id n))) r))))

  (def h (heap))

  (insert-n h 1000)
  (= (range 1000)
    (dequeue-all conj [] (impl accept h)))


  )