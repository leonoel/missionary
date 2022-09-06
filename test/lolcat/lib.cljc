(ns lolcat.lib
  (:require [lolcat.core :as lc])
  #?(:clj (:import (clojure.lang IFn IDeref))))

(def dup (lc/copy 0))

(def swap (concat (lc/copy 1) (lc/drop 2)))

(defn check [pred]
  (concat
    (lc/push #(when-not (pred %) (throw (ex-info "Predicate failed." {:predicate pred :value %}))))
    swap (lc/call 1) (lc/drop 0)))

(defn change [f & args]
  (concat
    (lc/push f) swap
    (apply lc/push args)
    (lc/call (inc (count args)))))

(defn spawn [id & events]
  (concat
    (lc/push
      #(lc/event [:notified id])
      #(lc/event [:terminated id]))
    (apply lc/call 2 events)))

(defn transfer [& events]
  (concat
    (lc/copy 0)
    (lc/push deref) swap
    (apply lc/call 1 events)))

(defn deref-error [it]
  ((try (let [x @it] #(throw (ex-info "Not an error." {:value x})))
        (catch #?(:clj Throwable :cljs :default) e #(do e)))))

(defn crash [& events]
  (concat
    (lc/copy 0)
    (lc/push deref-error) swap
    (apply lc/call 1 events)))

(defn notified [id & events]
  (concat
    (check #{[:notified id]})
    (apply concat events)
    (lc/push nil)))

(defn terminated [id & events]
  (concat
    (check #{[:terminated id]})
    (apply concat events)
    (lc/push nil)))

(deftype It [id]
  IFn
  (#?(:clj invoke :cljs -invoke) [_]
    (lc/event [:cancelled id]))
  IDeref
  (#?(:clj deref :cljs -deref) [_]
    ((lc/event [:transferred id]))))

(defn spawned [id & programs]
  (concat
    (lc/push peek) (lc/copy 1) (lc/call 1) swap
    (change pop) (check #{[:spawned id]})
    (apply concat programs)
    (lc/push (->It id))))

(defn transferred [id & programs]
  (concat
    (check #{[:transferred id]})
    (apply concat programs)
    (change constantly)))

(defn throwing [e] #(throw e))

(defn crashed [id & programs]
  (concat
    (check #{[:transferred id]})
    (apply concat programs)
    (change throwing)))

(defn detect [id x]
  (lc/event [:detected id x]))

(defn detected [id & programs]
  (concat
    (lc/push peek) (lc/copy 1) (lc/call 1) swap
    (change pop) (check #{[:detected id]})
    (apply concat programs)))

(defn flow [id]
  (fn [n t] (lc/event [:spawned id #((if % t n))])))

(defn signal [& events]
  (concat (lc/copy 0) (lc/push false) (apply lc/call 1 events) (lc/drop 0)))

(defn terminate [& events]
  (concat (lc/push true) (apply lc/call 1 events) (lc/drop 0)))

(defn cancel [& events]
  (concat (lc/copy 0) (apply lc/call 0 events) (lc/drop 0)))

(defn cancelled [id & programs]
  (concat
    (check #{[:cancelled id]})
    (apply concat programs)
    (lc/push nil)))
