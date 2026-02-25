(ns ^:no-doc missionary.impl.pairing-heap
  (:require [missionary.impl.varhandle :as v]
            [missionary.impl.util :as u]))

(def idle #?(:clj (Object.) :cljs (js-obj)))

(defmacro init [{:keys [state]} o]
  `(let [o# ~o] (v/set ~state o# idle)))

(defmacro accept [{:keys [state head tail child]} o]
  `(let [o# ~o]
     (loop []
       (let [s# (v/get-volatile ~state o#)]
         (when (identical? s# idle)
           (throw (u/error "Illegal state - empty heap")))
         (let [h# (v/get ~head s#)
               t# (v/get ~tail s#)]
           (if (identical? h# t#)
             (do (u/yield) (recur))
             (if (v/compare-and-set ~state o# s# idle)
               (do (v/set ~child h# t#) h#)
               (recur))))))))

(defmacro insert [{:keys [lt ready state head tail child sibling mk-state]} o x]
  `(let [o# ~o
         x# ~x]
     (loop []
       (let [s# (v/get-volatile ~state o#)]
         (if (identical? s# idle)
           (if (v/compare-and-set ~state o# s# (~mk-state x# nil))
             (~ready o#)
             (recur))
           (let [h# (v/get ~head s#)
                 t# (v/get ~tail s#)]
             (if (identical? h# t#)
               (do (u/yield) (recur))
               (if (~lt h# x#)
                 (do (v/set ~sibling x# t#)
                     (when-not (v/compare-and-set ~state o# s# (~mk-state h# x#))
                       (v/set ~sibling x# nil)
                       (recur)))
                 (if (v/compare-and-set ~state o# s# (~mk-state h# h#))
                   (do (v/set ~child h# t#)
                       (v/set-volatile ~state o# (~mk-state x# h#)))
                   (recur))))))))))

(defmacro meld [{:keys [lt child sibling]} x y]
  `(let [x# ~x
         y# ~y]
     (if (~lt x# y#)
       (do (v/set ~sibling y# (v/get ~child x#))
           (v/set ~child x# y#) x#)
       (do (v/set ~sibling x# (v/get ~child y#))
           (v/set ~child y# x#) y#))))

(defmacro get-and-set [f x y]
  `(let [x# ~x
         y# ~y
         z# (v/get ~f x#)]
     (v/set ~f x# y#) z#))

(defmacro dequeue [{:keys [child sibling] :as impl} h]
  `(when-some [head# (get-and-set ~child ~h nil)]
     (loop [heap# nil
            prev# nil
            head# head#]
       (let [next# (v/get ~sibling head#)]
         (v/set ~sibling head# nil)
         (if (nil? prev#)
           (if (nil? next#)
             (if (nil? head#)
               heap# (if (nil? heap#) head# (meld ~impl heap# head#)))
             (recur heap# head# next#))
           (let [head# (meld ~impl prev# head#)
                 heap# (if (nil? heap#) head# (meld ~impl heap# head#))]
             (if (nil? next#) heap# (recur heap# nil next#))))))))

(defmacro dequeue2 [{:keys [child sibling] :as impl} h]
  `(when-some [x# (get-and-set ~child ~h nil)]
     (loop [x# (loop [s# nil
                      x# x#]
                 (if-some [y# (get-and-set ~sibling x# nil)]
                   (let [z# (get-and-set ~sibling y# nil)
                         m# (meld ~impl x# y#)]
                     (v/set ~sibling m# s#)
                     (if (nil? z#) m# (recur m# z#)))
                   (do (v/set ~sibling x# s#) x#)))
            y# (get-and-set ~sibling x# nil)]
       (if (nil? y#)
         x# (let [z# (get-and-set ~sibling y# nil)]
              (recur (meld ~impl x# y#) z#))))))

(defmacro dequeue3 [{:keys [child sibling] :as impl} h]
  `(when-some [x# (get-and-set ~child ~h nil)]
     (if-some [y# (get-and-set ~sibling x# nil)]
       (loop [z# (get-and-set ~sibling y# nil)
              h# (meld ~impl x# y#)]
         (if (nil? z#)
           h# (if-some [y# (get-and-set ~sibling z# nil)]
                (recur (get-and-set ~sibling y# nil)
                  (meld ~impl h# (meld ~impl z# y#)))
                (meld ~impl h# z#)))) x#)))

(defmacro enqueue [impl h x]
  `(let [h# ~h
         x# ~x]
     (if (nil? h#) x# (meld ~impl h# x#))))

(defmacro defimpl [sym & {:as impl}]
  (let [f (gensym)
        args (gensym)]
    `(defmacro ~sym [~f & ~args]
       (cons ~f (cons ~(list `quote impl) ~args)))))

(comment
  (deftype HeapState [head tail])
  (deftype Node [id ^:unsynchronized-mutable child ^:unsynchronized-mutable sibling])
  (deftype Heap [^:unsynchronized-mutable state])

  (defn id [^Node n]
    (.-id n))

  (defmacro ready [_]
    `(prn :ready))

  (defmacro lt [x y]
    `(< (id ~x) (id ~y)))

  (defmacro mk-heap-state [h t]
    `(->HeapState ~h ~t))

  (defimpl impl
    :lt       lt
    :ready    ready
    :mk-state mk-heap-state
    :state    Heap/state
    :head     HeapState/head
    :tail     HeapState/tail
    :child    Node/child
    :sibling  Node/sibling)

  (defn heap []
    (let [h (->Heap nil)]
      (impl init h) h))

  (defn insert-node [h id]
    (impl insert h
      (->Node id nil nil)) h)

  (defn insert-n [h n]
    (reduce insert-node h
      (shuffle (range n))))

  (defn dequeue-all [rf r n]
    (loop [n n
           r (rf r (id n))]
      (if (reduced? r)
        @r (if-some [n (impl dequeue3 n)]
             (recur n (rf r (id n))) r))))

  (def h (heap))

  (insert-n h 1000)
  (= (range 1000)
    (dequeue-all conj []
      (impl accept h)))

  ;; N writers, 1 reader
  ;; first insert triggers ready callback
  ;; accept must be called after ready
  ;; queue becomes empty after accept
  ;; next insert will re-trigger ready callback
  ;; thread safety
  ;; no lost items
  (impl insert heap node)                                   ;; concurrent
  (impl accept heap)                                        ;; concurrent


  )