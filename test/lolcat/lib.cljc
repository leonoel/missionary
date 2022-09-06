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

(defn insert [id]
  (concat                                                   ;; [{} value]
    (lc/push assoc)                                         ;; [{} value assoc]
    (lc/copy 2)                                             ;; [{} value assoc {}]
    (lc/drop 3)                                             ;; [value assoc {}]
    (lc/push id)                                            ;; [value assoc {} id]
    (lc/copy 3)                                             ;; [value assoc {} id value]
    (lc/drop 4)                                             ;; [assoc {} id value]
    (lc/call 3)))                                           ;; [{id value}]

(defn spawn [id & events]
  (concat
    (lc/push
      #(lc/event [:notified id])
      #(lc/event [:terminated id]))
    (apply lc/call 2 events)                                ;; [{} iterator]
    (insert id)))                                           ;; [{id iterator}]

(defn transfer-with [f id & events]
  (concat                                                   ;; [{id iterator}]
    (lc/push f get)                                         ;; [{id iterator} deref get]
    (lc/copy 2)                                             ;; [{id iterator} deref get {id iterator}]
    (lc/push id)                                            ;; [{id iterator} deref get {id iterator} id]
    (lc/call 2)                                             ;; [{id iterator} deref iterator]
    (apply lc/call 1 events)))                              ;; [{id iterator} result]

(def transfer (partial transfer-with deref))

(defn capture-error [f & args]
  ((try (let [x (apply f args)] #(throw (ex-info "Not an error." {:value x})))
        (catch #?(:clj Throwable :cljs :default) e #(do e)))))

(def crash (partial transfer-with (partial capture-error deref)))

(defn signal [id arg & events]
  (concat
   (lc/copy 0)
   (change get id)
   (lc/push arg)
   (apply lc/call 1 events)
   (lc/drop 0)))

(defn signal-error [id arg & events]
  (concat
   (lc/push capture-error)
   (lc/copy 1)
   (change get id)
   (lc/push arg)
   (apply lc/call 2 events)
   (lc/drop 0)))

(defn notify [id & events]
  (apply signal id false events))

(defn terminate [id & events]
  (apply signal id true events))

(defn cancel [id & events]
  (concat
    (lc/copy 0)
    (change get id)
    (apply lc/call 0 events)
    (lc/drop 0)))

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
  (concat                                                   ;; [{} [:spawned id #((if % t n))]]
    (lc/push peek)                                          ;; [{} [:spawned id #((if % t n))] peek]
    (lc/copy 1)                                             ;; [{} [:spawned id #((if % t n))] peek [:spawned id #((if % t n))]]
    (lc/call 1)                                             ;; [{} [:spawned id #((if % t n))] #((if % t n))]
    swap                                                    ;; [{} #((if % t n)) [:spawned id #((if % t n))]]
    (change pop)                                            ;; [{} #((if % t n)) [:spawned id]]
    (check #{[:spawned id]})                                ;; [{} #((if % t n))]
    (insert id)                                             ;; [{id #((if % t n))}]
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

(defn cancelled [id & programs]
  (concat
    (check #{[:cancelled id]})
    (apply concat programs)
    (lc/push nil)))

(defn store [& programs]
  (concat
    (lc/push {})
    (apply concat programs)
    (lc/drop 0)))
