(ns missionary.impl.Seed
  #?(:cljs (:import missionary.Cancelled)
     :cljd (:require [missionary.Cancelled :refer [Cancelled]])))

(declare cancel transfer)
(deftype Process
  [^:mutable iterator notifier terminator]
  #?(:cljs IFn :cljd cljd.core/IFn)
  (-invoke [ps] (cancel ps))
  #?(:cljs IDeref :cljd cljd.core/IDeref)
  (-deref [ps] (transfer ps)))

(defn cancel [^Process ps]
  (set! (.-iterator ps) nil))

#?(:cljs
   (defn more [^Process ps i]
     (if (.hasNext i)
       ((.-notifier ps))
       (do (set! (.-iterator ps) nil)
           ((.-terminator ps)))))
   :cljd
   (defn more [^Process ps i]
     (if (.moveNext i)
       ((.-notifier ps))
       (do (set! (.-iterator ps) nil)
           ((.-terminator ps))))))

#?(:cljs
   (defn transfer [^Process ps]
     (if-some [i (.-iterator ps)]
       (let [x (.next i)]
         (more ps i) x)
       (do ((.-terminator ps))
           (throw (Cancelled. "Seed cancelled")))))
   :cljd
   (defn transfer [^Process ps]
     (if-some [i (.-iterator ps)]
       (let [x (.-current i)]
         (more ps i) x)
       (do ((.-terminator ps))
           (throw (Cancelled. "Seed cancelled"))))))

#?(:cljs
   (defn run [coll n t]
     (let [i (iter coll)
           ps (->Process i n t)]
       (more ps i) ps))
   :cljd
   (defn run [coll n t]
     (let [i (.-iterator coll)
           ps (->Process i n t)]
       (if (.moveNext i)
         ((.-notifier ps))
         (do (set! (.-iterator ps) nil)
             ((.-terminator ps))))
       ps)))
