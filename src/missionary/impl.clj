(ns missionary.impl
  (:require
    [cloroutine.impl :refer [safe]]
    [missionary.misc :as m])
  (:import (java.util.concurrent.atomic AtomicReference)
           (java.util.concurrent Executor CountDownLatch)))


;;;;;;;;;;;;
;; TOKENS ;;
;;;;;;;;;;;;

(defn token []
  (AtomicReference. m/nop))

(defn valid? [^AtomicReference token]
  (some? (.get token)))

(defn consume! [^AtomicReference token]
  (loop []
    (when-some [! (.get token)]
      (if (.compareAndSet token ! nil)
        (!) (recur)))))

(defn alienate! [^AtomicReference token c]
  (loop []
    (if-some [! (.get token)]
      (when-not (.compareAndSet token ! c)
        (recur)) (c))))


;;;;;;;;;;;;;;;;;;;;;
;; SYNCHRONIZATION ;;
;;;;;;;;;;;;;;;;;;;;;

(defn dfv []
  (let [state (AtomicReference. #{})]
    (fn
      ([t]
       (let [x (.get state)]
         (if (set? x)
           (if (.compareAndSet state x (reduced t))
             (do (reduce m/send-rf t x) t)
             (recur t)) @x)))
      ([s! f!]
       (let [x (.get state)]
         (if (set? x)
           (let [! #(s! %)]
             (if (.compareAndSet state x (conj x !))
               #(let [x (.get state)]
                  (when (set? x)
                    (when (contains? x !)
                      (if (.compareAndSet state x (disj x !))
                        (f! m/dfv-dereference-cancelled)
                        (recur)))))
               (recur s! f!)))
           (do (s! @x) m/nop)))))))

(defn rdv []
  (let [state (AtomicReference. nil)]
    (fn
      ([t]
       (fn [s! f!]
         (let [x (.get state)]
           (if (set? x)
             (let [r (first x)]
               (if (.compareAndSet state x (m/disj-nil x r))
                 (do (r t) (s! nil) m/nop) (recur s! f!)))
             (let [w #(s! nil)]
               (if (.compareAndSet state x (assoc x w t))
                 #(let [x (.get state)]
                    (when (contains? x w)
                      (if (.compareAndSet state x (m/dissoc-nil x w))
                        (f! m/rdv-give-cancelled) (recur))))
                 (recur s! f!)))))))
      ([s! f!]
       (let [x (.get state)]
         (if (map? x)
           (let [[w u] (first x)]
             (if (.compareAndSet state x (m/dissoc-nil x w))
               (do (w) (s! u) m/nop) (recur s! f!)))
           (let [r #(s! %)]
             (if (.compareAndSet state x (conj (or x #{}) r))
               #(let [x (.get state)]
                  (when (contains? x r)
                    (if (.compareAndSet state x (m/disj-nil x r))
                      (f! m/rdv-take-cancelled) (recur))))
               (recur s! f!)))))))))

(defn buf []
  (let [state (AtomicReference. nil)]
    (fn
      ([t]
       (let [x (.get state)]
         (if (set? x)
           (let [r (first x)]
             (if (.compareAndSet state x (m/disj-nil x r)) (r t) (recur t)))
           (when-not (.compareAndSet state x (conj (or x []) t)) (recur t)))))
      ([s! f!]
       (let [x (.get state)]
         (if (vector? x)
           (if (.compareAndSet state x (when-not (== 1 (count x)) (subvec x 1)))
             (do (s! (nth x 0)) m/nop) (recur s! f!))
           (let [r #(s! %)]
             (if (.compareAndSet state x (conj (or x #{}) r))
               #(let [x (.get state)]
                  (when (contains? x r)
                    (if (.compareAndSet state x (m/disj-nil x r))
                      (f! m/buf-dequeue-cancelled) (recur))))
               (recur s! f!)))))))))

(defn sem
  ([] (sem 1))
  ([n]
   (let [state (AtomicReference. n)]
     (fn
       ([]
        (let [x (.get state)]
          (if (set? x)
            (let [r (first x)]
              (if (.compareAndSet state x (m/disj-nil x r)) (r) (recur)))
            (when-not (.compareAndSet state x (if (nil? x) 1 (inc x))) (recur)))))
       ([s! f!]
        (let [x (.get state)]
          (if (number? x)
            (if (.compareAndSet state x (if (== 1 x) nil (dec x)))
              (do (s! nil) m/nop) (recur s! f!))
            (let [r #(s! nil)]
              (if (.compareAndSet state x (conj (or x #{}) r))
                #(let [x (.get state)]
                   (when (contains? x r)
                     (if (.compareAndSet state x (m/disj-nil x r))
                       (f! m/sem-acquire-cancelled) (recur))))
                (recur s! f!))))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PARALLEL COMPOSITION ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn join-many [ts]
  (fn [s! f!]
    (let [n (count ts)
          a (object-array n)
          s (AtomicReference. [n n nil])
          c (->> ts
                 (into []
                       (map-indexed
                         (fn [i t]
                           (t (fn [x]
                                (aset a i x)
                                (loop []
                                  (let [old (.get s)
                                        err (nth old 1)
                                        rem (dec (nth old 0))]
                                    (if (.compareAndSet s old (assoc old 0 rem))
                                      (when (zero? rem)
                                        (if (== n err)
                                          (s! (vec a))
                                          (f! (aget a err))))
                                      (recur)))))
                              (fn [e]
                                (aset a i e)
                                (loop []
                                  (let [old (.get s)
                                        err (nth old 1)
                                        rem (dec (nth old 0))]
                                    (if (== n err)
                                      (if (.compareAndSet s old (assoc old 0 rem 1 i))
                                        (if (zero? rem)
                                          (f! e)
                                          (when-some [c (nth old 2)] (c)))
                                        (recur))
                                      (if (.compareAndSet s old (assoc old 0 rem))
                                        (when (zero? rem) (f! (aget a err)))
                                        (recur))))))))))
                 (partial reduce m/call-rf nil))]
      (loop []
        (let [old (.get s)
              rem (nth old 0)
              err (nth old 1)]
          (if (zero? rem)
            m/nop
            (if (.compareAndSet s old (assoc old 2 c))
              (if (== n err) c (do (c) m/nop))
              (recur))))))))


;;;;;;;;;;;;
;; TIMING ;;
;;;;;;;;;;;;

;; slower
#_
(let [scheduler (ScheduledThreadPoolExecutor. 1)]
  (defn sleep [t x]
    (fn [s! f!]
      (place [^boolean pending true]
        (let [fut (.schedule ^ScheduledExecutorService scheduler
                             (reify Runnable (run [_] (set! pending false) (s! x)))
                             (long t) TimeUnit/MILLISECONDS)]
          #(when (.cancel ^Future fut false)
             (when pending (f! m/sleep-cancelled))))))))

(let [pending (AtomicReference. (sorted-map))
      scheduler (doto (Thread. (reify Runnable
                                 (run [_]
                                   (loop []
                                     (try
                                       (when-some [prev (.get pending)]
                                         (if-some [[time item] (first prev)]
                                           (let [delay (unchecked-subtract time (System/currentTimeMillis))]
                                             (if (pos? delay)
                                               (Thread/sleep delay)
                                               (when (.compareAndSet pending prev (dissoc prev time))
                                                 (if (set? item) (reduce m/call-rf nil item) (item)))))
                                           (Thread/sleep Long/MAX_VALUE)))
                                       (catch InterruptedException _
                                         (Thread/interrupted)))
                                     (recur))))
                               "Missionary Scheduler")
                  (.setDaemon true)
                  (.start))]
  (defn sleep [t x]
    (fn [s! f!]
      (loop []
        (let [prev (.get pending)
              time (+ (System/currentTimeMillis) t)
              slot #(s! x)
              item (get prev time)
              curr (assoc prev time (if (nil? item) slot (if (set? item) (conj item slot) #{slot item})))]
          (if (.compareAndSet pending prev curr)
            (do (when (== time (-> curr first key))
                  (when (nil? item) (.interrupt scheduler)))
                #(let [prev (.get pending)]
                   (when-some [item (get prev time)]
                     (if (.compareAndSet
                           pending prev
                           (if (set? item)
                             (assoc prev time (disj item slot))
                             (dissoc prev time)))
                       (f! m/sleep-cancelled) (recur)))))
            (recur)))))))


;;;;;;;;;;;;;;;
;; THREADING ;;
;;;;;;;;;;;;;;;

(defn via [^Executor exec thunk]
  (fn [s! f!]
    (let [token (token)]
      (.execute exec (reify Runnable
                       (run [_]
                         (let [t (Thread/currentThread)]
                           (alienate! token #(.interrupt t))
                           (safe [e (s! (thunk))] (f! e))
                           (consume! token)
                           (Thread/interrupted)))))
      #(consume! token))))


;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SEQUENTIAL PROCESSES ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;

(let [fiber (ThreadLocal.)
      cont! (fn [^objects a prev]
              (safe [e (loop []
                         (.set fiber a)
                         (let [x ((aget a 3))]
                           (if (identical? x a)
                             (when (nil? (.get fiber)) (recur))
                             ((aget a 0) x))))]
                ((aget a 1) e)
                (.set fiber prev)))
      callb (fn [^objects a s]
              (fn [r]
                (aset a 6 s)
                (aset a 7 r)
                (let [prev (.get fiber)]
                  (if (identical? prev a)
                    (.set fiber nil)
                    (cont! a prev)))))]

  (defn ?
    ([]
     (if-some [f ^objects (.get fiber)]
       (do (if (valid? (aget f 2)) ((aget f 4) nil) ((aget f 5) m/process-cancelled)) f)
       (when (.isInterrupted (Thread/currentThread))
         (throw m/process-cancelled))))
    ([t]
     (if-some [f ^objects (.get fiber)]
       (do (alienate! (aget f 2) (t (aget f 4) (aget f 5))) f)
       (let [l (CountDownLatch. 1)
             a (object-array 2)
             c (t (fn [x] (doto a (aset 0 true) (aset 1 x)) (.countDown l))
                  (fn [x] (doto a (aset 0 false) (aset 1 x)) (.countDown l)))]
         (try (.await l)
              (catch InterruptedException _
                (c) (.await l)
                (.interrupt (Thread/currentThread))))
         (if (aget a 0) (aget a 1) (throw (aget a 1)))))))


  (defn ! []
    (let [f (.get fiber)
          s (aget f 6)
          r (aget f 7)]
      (aset f 6 nil)
      (aset f 7 nil)
      (if s r (throw r))))

  (defn sp [ctor]
    (fn [s! f!]
      (let [tok (token)
            arr (object-array 8)]
        (doto arr
          (aset 0 s!)
          (aset 1 f!)
          (aset 2 tok)
          (aset 3 (ctor))
          (aset 4 (callb arr true))
          (aset 5 (callb arr false))
          (cont! (.get fiber)))
        #(consume! tok)))))
