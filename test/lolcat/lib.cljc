(ns lolcat.lib
  (:require [lolcat.core :as lc]
            [clojure.test :as t])
  #?(:clj (:import (clojure.lang IFn IDeref))))

(def dup (lc/copy 0))

(def swap (concat (lc/copy 1) (lc/drop 2)))

(def -rot                               ; x y z -- z x y
  (concat (lc/copy 2) (lc/copy 2) (lc/drop 3) (lc/drop 3)))

(defn -rotn [n]
  (let [x (dec n)]
    (into [] (comp cat cat) [(repeat x (lc/copy x)) (repeat x (lc/drop n))])))

(t/deftest -rotn-test
  (t/is (= swap (-rotn 2)))
  (t/is (= -rot (-rotn 3))))

(defn transfer-with [f id & events]
  (concat dup (lc/push id get)          ;; store store id get
    (lc/call 2)                         ;; store store[id]
    (lc/push f)                         ;; store store[id] f
    (apply lc/call 1 events)            ;; store f(store[id])
    ))

(defn bi [f g]
  (concat dup (lc/push f) (lc/call 1) swap (lc/push g) (lc/call 1)))

(t/deftest bi-test
  (t/is (= [[1 2] 3]
           (lc/run [[1 2 3]] (bi pop peek)))))

(defn check [pred]
  (concat
    (lc/push #(when-not (pred %) (throw (ex-info "Predicate failed." {:predicate pred :value %}))))
    (lc/call 1) (lc/drop 0)))

(defn change [f & args]
  (concat
    (apply lc/push args)
    (lc/push f)
    (lc/call (inc (count args)))))

(defn insert [id]
  (concat (lc/push id) swap (lc/push assoc) (lc/call 3)))

(defn spawn [id & events]
  (concat                                                   ;; [{} flow]
    (lc/push #(lc/event [:notified id])) swap               ;; [{} notifier flow]
    (lc/push #(lc/event [:terminated id])) swap             ;; [{} notifier terminator flow]
    (apply lc/call 2 events)                                ;; [{} iterator]
    (insert id)))                                           ;; [{id iterator}]

(defn transfer-with [f id & events]
  (concat                                                   ;; [{id iterator}]
    (lc/copy 0)                                             ;; [{id iterator} {id iterator}]
    (lc/push id get)                                        ;; [{id iterator} {id iterator} id get]
    (lc/call 2)                                             ;; [{id iterator} iterator]
    (lc/push f)                                             ;; [{id iterator} iterator f]
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
    (lc/push arg) swap
    (apply lc/call 1 events)
    (lc/drop 0)))

(defn signal-error [id arg & events]
  (concat
    (lc/copy 0)
    (change get id)
    (lc/push arg)
    (lc/push capture-error)
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
  (concat                               ;; [{} [:spawned id #()]]
    (bi peek pop)                       ;; [{} #() [:spawned id]]
    (check #{[:spawned id]})            ;; [{} #()]
    (insert id)
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
    (lc/copy 0) (change peek) swap
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

