(ns missionary.impl.Rendezvous
  #?(:cljs (:import missionary.Cancelled)
     :cljd (:require [missionary.Cancelled :refer [Cancelled]])))

(defn nop [])

(declare give-cb take-cb cancel-give cancel-take)

(deftype Port [^:mutable readers
               ^:mutable writers]
  #?(:cljs IFn :cljd cljd.core/IFn)
  (-invoke [this t]
    (fn [s! f!]
      (give-cb this t s! f!)))
  (-invoke [this s! f!]
    (take-cb this s! f!)))

(defn give-cb [^Port p t s! f!]
  (if-some [[!] (seq (.-readers p))]
    (do (set! (.-readers p) (disj (.-readers p) !))
        (! t) (s! nil) nop)
    (let [! #(s! nil)]
      (set! (.-writers p) (assoc (.-writers p) ! t))
      #(cancel-give p ! f!))))

(defn take-cb [^Port p s! f!]
  (if-some [[[! t]] (seq (.-writers p))]
    (do (set! (.-writers p) (dissoc (.-writers p) !))
        (!) (s! t) nop)
    (let [! #(s! %)]
      (set! (.-readers p) (conj (.-readers p) !))
      #(cancel-take p ! f!))))

(defn cancel-give [^Port p ! f!]
  (when (contains? (.-writers p) !)
    (set! (.-writers p) (dissoc (.-writers p) !))
    (f! (Cancelled. "Rendez-vous give cancelled."))))

(defn cancel-take [^Port p ! f!]
  (when (contains? (.-readers p) !)
    (set! (.-readers p) (disj (.-readers p) !))
    (f! (Cancelled. "Rendez-vous take cancelled."))))

(defn make [] (->Port #{} {}))
