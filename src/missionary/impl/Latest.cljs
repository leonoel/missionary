(ns ^:no-doc missionary.impl.Latest
  (:require [missionary.impl.Heap :as h]))

(declare kill transfer)

(deftype Process [combinator notifier terminator value args inputs dirty ^number alive]
  IFn
  (-invoke [ps] (kill ps))
  IDeref
  (-deref [ps] (transfer ps)))

(defn kill [^Process ps]
  (let [inputs (.-inputs ps)]
    (dotimes [i (alength inputs)]
      ((aget inputs i)))))

(defn transfer [^Process ps]
  (let [c (.-combinator ps)
        args (.-args ps)
        inputs (.-inputs ps)
        dirty (.-dirty ps)
        x (.-value ps)
        x (try (set! (.-value ps) ps)
               (when (nil? args) (throw (js/Error. "Undefined continuous flow.")))
               (loop [x x]
                 (let [i (h/dequeue dirty)
                       p (aget args i)]
                   (aset args i @(aget inputs i))
                   (let [x (if (identical? x ps)
                             x (if (= p (aget args i))
                                 x ps))]
                     (if (zero? (h/size dirty))
                       (if (identical? x ps)
                         (let [x (.apply c nil args)]
                           (if (zero? (h/size dirty))
                             x (recur x))) x)
                       (recur x)))))
               (catch :default e
                 (kill ps)
                 (loop []
                   (when (pos? (h/size dirty))
                     (try @(aget inputs (h/dequeue dirty))
                          (catch :default _)) (recur)))
                 (set! (.-notifier ps) nil) e))]
    (set! (.-value ps) x)
    (when (zero? (.-alive ps))
      ((.-terminator ps)))
    (if (nil? (.-notifier ps))
      (throw x) x)))

(defn run [c fs n t]
  (let [it (iter fs)
        arity (count fs)
        args (object-array arity)
        inputs (object-array arity)
        dirty (h/create arity)
        ps (->Process c n t nil nil inputs dirty arity)
        done #(when (zero? (set! (.-alive ps) (dec (.-alive ps))))
                (when-not (identical? (.-value ps) ps)
                  ((.-terminator ps))))]
    (set! (.-value ps) ps)
    (dotimes [index arity]
      (aset inputs index
        ((.next it)
         #(do (h/enqueue dirty index)
              (when (== 1 (h/size dirty))
                (when-not (identical? (.-value ps) ps)
                  (if-some [n (.-notifier ps)]
                    (n) (loop []
                          (try @(aget inputs (h/dequeue dirty))
                               (catch :default _))
                          (when (pos? (h/size dirty))
                            (recur))))))) done)))
    (when (== (h/size dirty) arity)
      (set! (.-args ps) args)) (n) ps))