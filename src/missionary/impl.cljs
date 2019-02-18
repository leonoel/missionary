(ns missionary.impl
  (:require [missionary.misc :as m])
  (:require-macros [cloroutine.impl :refer [safe]]))


;;;;;;;;;;;;
;; TOKENS ;;
;;;;;;;;;;;;

(defn token []
  (volatile! m/nop))

(defn valid? [token]
  (nil? @token))

(defn consume! [token]
  (when-some [! @token]
    (vreset! token nil) (!)))

(defn alienate! [token c]
  (if (some? @token)
    (vreset! token c) (c)))


;;;;;;;;;;;;;;;;;;;;;
;; SYNCHRONIZATION ;;
;;;;;;;;;;;;;;;;;;;;;

(deftype DataFlowVariable [^:mutable bound
                           ^:mutable value
                           ^:mutable watch]
  IFn
  (-invoke [_ t]
    (when-not bound
      (set! bound true)
      (set! value t)
      (reduce m/send-rf t (persistent! watch))
      (set! watch nil)))
  (-invoke [_ s! f!]
    (if bound
      (do (s! value) m/nop)
      (let [! #(s! %)]
        (set! watch (conj! watch !))
        #(when-not bound
           (when (contains? watch !)
             (set! watch (disj! watch !))
             (f! m/dfv-dereference-cancelled)))))))

(defn dfv []
  (->DataFlowVariable false nil (transient #{})))

(deftype RendezVous [^:mutable readers
                     ^:mutable writers]
  IFn
  (-invoke [_ t]
    (fn [s! f!]
      (if-some [[!] (seq readers)]
        (do (set! readers (disj readers !))
            (! t) (s! nil) m/nop)
        (let [! #(s! nil)]
          (set! writers (assoc writers ! t))
          #(when (contains? writers !)
             (set! writers (dissoc writers !))
             (f! m/rdv-give-cancelled))))))
  (-invoke [_ s! f!]
    (if-some [[[! t]] (seq writers)]
      (do (set! writers (dissoc writers !))
          (!) (s! t) m/nop)
      (let [! #(s! %)]
        (set! readers (conj readers !))
        #(when (contains? readers !)
           (set! readers (disj readers !))
           (f! m/rdv-take-cancelled))))))


(defn rdv []
  (->RendezVous #{} {}))

(deftype Buffer [^:mutable enqueue
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
             (f! m/buf-dequeue-cancelled)))
        (let [tmp enqueue]
          (set! enqueue dequeue)
          (set! dequeue (.reverse tmp))
          (s! (.pop tmp)) m/nop))
      (do (s! (.pop dequeue)) m/nop))))

(defn buf []
  (->Buffer (array) (array) #{}))


(deftype Semaphore [^:mutable available
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
           (f! m/sem-acquire-cancelled)))
      (do (set! available (dec available))
          (s! nil) m/nop))))

(defn sem
  ([] (sem 1))
  ([n] (->Semaphore n #{})))


;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PARALLEL COMPOSITION ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype PendingJoin [s! f! results
                      ^:mutable pending
                      ^:mutable failure
                      ^:mutable cancels]
  IFn
  (-invoke [_ cs]
    (if (zero? pending)
      m/nop (if (== (alength results) failure)
              (do (set! cancels cs) #(reduce m/call-rf nil cs))
              (do (reduce m/call-rf nil cs) m/nop))))
  (-invoke [_ i s x]
    (aset results i x)
    (set! pending (dec pending))
    (if s
      (when (zero? pending)
        (if (== (alength results) failure)
          (s! (vec results))
          (f! (aget results failure))))
      (if (== (alength results) failure)
        (do (set! failure i)
            (if (zero? pending)
              (f! x) (reduce m/call-rf nil cancels)))
        (when (zero? pending) (f! (aget results failure)))))))

(defn join-many [ts]
  (fn [s! f!]
    (let [n  (count ts)
          p  (->PendingJoin s! f! (object-array n) n n nil)]
      (p (into [] (map-indexed (fn [i t] (t #(p i true %) #(p i false %)))) ts)))))


;;;;;;;;;;;;
;; TIMING ;;
;;;;;;;;;;;;

(defn sleep [d x]
  (fn [s! f!]
    (let [pd (volatile! true)
          id (js/setTimeout #(do (vreset! pd false) (s! x)) d)]
      #(when @pd
         (vreset! pd false)
         (js/clearTimeout id)
         (f! m/sleep-cancelled)))))


;;;;;;;;;;;;;;;
;; THREADING ;;
;;;;;;;;;;;;;;;

(def threading-unsupported (ex-info "Threading operations are not supported." {}))

(def via (constantly threading-unsupported))


;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SEQUENTIAL PROCESSES ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;

(let [fiber (volatile! nil)
      cont! (fn [a prev]
              (safe [e (loop []
                         (vreset! fiber a)
                         (let [x ((aget a 3))]
                           (if (identical? x a)
                             (when (nil? @fiber) (recur))
                             ((aget a 0) x))))]
                ((aget a 1) e)
                (vreset! fiber prev)))
      callb (fn [a s]
              (fn [r]
                (aset a 6 s)
                (aset a 7 r)
                (let [prev @fiber]
                  (if (identical? prev a)
                    (vreset! fiber nil)
                    (cont! a prev)))))]
  (defn ?
    ([]
     (let [f @fiber]
       (when (nil? f) (throw threading-unsupported))
       (if (some? (aget f 2)) ((aget f 4) nil) ((aget f 5) m/process-cancelled)) f))
    ([t]
     (let [f @fiber]
       (when (nil? f) (throw threading-unsupported))
       (let [c (t (aget f 4) (aget f 5))]
         (if (nil? (aget f 2))
           (c) (aset f 2 c)) f))))

  (defn ! []
    (let [f @fiber
          s (aget f 6)
          r (aget f 7)]
      (aset f 6 nil)
      (aset f 7 nil)
      (if s r (throw r))))

  (defn sp [ctor]
    (fn [s! f!]
      (let [arr (object-array 8)]
        (doto arr
          (aset 0 s!)
          (aset 1 f!)
          (aset 2 m/nop)
          (aset 3 (ctor))
          (aset 4 (callb arr true))
          (aset 5 (callb arr false))
          (cont! @fiber))
        #(when-some [! (aget arr 2)]
           (aset arr 2 nil) (!))))))