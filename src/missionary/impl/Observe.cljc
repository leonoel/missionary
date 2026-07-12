(ns ^:no-doc missionary.impl.Observe
  #?(:cljs (:import missionary.Cancelled)
     :cljd (:require [missionary.Cancelled :refer [Cancelled]])))

(declare kill transfer event-cb)

(deftype Process [^:mutable notifier terminator ^:mutable unsub ^:mutable value]
  #?(:cljs IFn :cljd cljd.core/IFn)
  (-invoke [this] (kill this) nil)
  #?(:cljs IDeref :cljd cljd.core/IDeref)
  (-deref [this] (transfer this)))

(defn kill [^Process ps]
  (when-some [cb (.-notifier ps)]
    (set! (.-notifier ps) nil)
    (try ((.-unsub ps))
         (set! (.-unsub ps) (Cancelled. "Observe cancelled."))
         (catch #?(:cljs :default :cljd Exception) e
           (set! (.-unsub ps) e)))
    (let [x (.-value ps)]
      (set! (.-value ps) nil)
      (when (identical? x ps) (cb)))))

(defn transfer [^Process ps]
  (if (nil? (.-notifier ps))
    (do ((.-terminator ps))
        (throw (.-unsub ps)))
    (let [x (.-value ps)]
      (set! (.-value ps) ps) x)))

(defn event-cb [^Process ps x]
  (when-some [cb (.-notifier ps)]
    (if (identical? ps (.-value ps))
      (do (set! (.-value ps) x) (cb))
      (throw (#?(:cljs js/Error. :cljd Exception.) "Can't process event - consumer is not ready.")))))

(defn run [s n t]
  (let [ps (->Process n t nil nil)]
    (set! (.-value ps) ps)
    (try (set! (.-unsub ps)
           (s (fn [x] (event-cb ps x))))
         (catch #?(:cljs :default :cljd Exception) e
           (set! (.-unsub ps) e)
           (set! (.-notifier ps) nil)
           (if (identical? ps (.-value ps))
             (n) (set! (.-value ps) ps)))) ps))
