(ns lolcat.lib
  (:require
   [clojure.string :as s]
   [clojure.test :as t]
   [lolcat.core :as lc])
  #?(:clj (:import (clojure.lang IDeref IFn))))

(lc/defword compose [& insts] insts)

(lc/defword dup [] [(lc/copy 0)])
(lc/defword over [] [(lc/copy 1)])
(lc/defword lose [] [(lc/drop 0)])

(lc/defword swap [] [(lc/copy 1) (lc/drop 2)])

(lc/defword -rot []                               ; x y z -- z x y
  [(lc/copy 2) (lc/copy 2) (lc/drop 3) (lc/drop 3)])

(lc/defword -rotn [n]
  (let [x (dec n)]
    (into [] cat [(repeat x (lc/copy x)) (repeat x (lc/drop n))])))

(t/deftest -rotn-test
  (t/is (= [2 1] (lc/run 1 2 (-rotn 2))))
  (t/is (= [3 1 2] (lc/run 1 2 3 (-rotn 3)))))

(lc/defword bi [f g]
  [(dup) f (lc/call 1) (swap) g (lc/call 1)])

(t/deftest bi-test
  (t/is (= [[1 2] 3] (lc/run [1 2 3] (bi pop peek)))))

(lc/defword check [pred]
  [#(when-not (pred %)
      (let [words (:words (lc/context identity))]
        (throw (ex-info (str "Predicate failed in:\n\n" (s/join " " words)
                          "\n\n\t" pred "\n\ndidn't match\n\n\t" % "\n")
                 {:predicate pred :value % :words (:words (lc/context identity))}))))
   (lc/call 1) (lc/drop 0)])

(lc/defword change [f & args]
  (-> []
    (into args)
    (conj f (lc/call (inc (count args))))))

(lc/defword insert [id]
  [id (swap) assoc (lc/call 3)])

(lc/defword lookup [id]
  [(lc/copy 0) (change get id)])

(lc/defword perform [id & events]                             ;; [{} flow]
  [(fn ([] (lc/event [:notified id]))
     ([r] (lc/event [:succeeded id r]))) (swap)             ;; [{} notifier flow]
   (fn ([] (lc/event [:terminated id]))
     ([r] (lc/event [:failed id r]))) (swap)                ;; [{} notifier terminator flow]
   (apply lc/call 2 events)                                 ;; [{} iterator]
   (insert id)])                                            ;; [{id iterator}]

(def spawn perform)

(defn transfer-with [f id & events]                         ;; [{id iterator}]
  [(lookup id)                                              ;; [{id iterator} iterator]
   f                                                        ;; [{id iterator} iterator f]
   (apply lc/call 1 events)])                               ;; [{id iterator} result]

(lc/defword transfer [id & events]
  (apply transfer-with deref id events))

(lc/defword transfer= [v id & events]
  [(apply transfer id events) (check (partial = v))])

(defn capture-error [f & args]
  ((try (let [x (apply f args)] #(throw (ex-info "Not an error." {:value x})))
        (catch #?(:clj Throwable :cljs :default) e #(do e)))))

(lc/defword crash [id & events]
  (apply transfer-with (partial capture-error deref) id events))

(lc/defword signal [id arg & events]
  [(lookup id)
   arg (swap)
   (apply lc/call 1 events)
   (lc/drop 0)])

(lc/defword signal-error [id arg & events]
  [(lookup id)
   arg capture-error
   (apply lc/call 2 events)
   (lc/drop 0)])

(lc/defword notify [id & events]
  [(apply signal id false events)])

(lc/defword terminate [id & events]
  [(apply signal id true events)])

(defn cancel! [ps] (ps))

(lc/defword cancel [id & events]
  [(lookup id) cancel!
   (apply lc/call 1 events)
   (lc/drop 0)])

(lc/defword notified [id & insts]
  (-> [(check #{[:notified id]})]
    (into insts)
    (conj nil)))

(lc/defword terminated [id & insts]
  (-> [(check #{[:terminated id]})]
    (into insts)
    (conj nil)))

(deftype It [id]
  IFn
  (#?(:clj invoke :cljs -invoke) [_]
    (lc/event [:cancelled id]))
  IDeref
  (#?(:clj deref :cljs -deref) [_]
    ((lc/event [:transferred id]))))

(lc/defword spawned [id & insts]                            ;; [{} [:spawned id #()]]
  (-> [(bi peek pop)                                        ;; [{} #() [:spawned id]]
       (check #{[:spawned id]})                             ;; [{} #()]
       (insert id)]
    (into insts)
    (conj (->It id))))

(lc/defword transferred [id & insts]
  (-> [(check #{[:transferred id]})]
    (into insts)
    (conj (change constantly))))

(defn throwing [e] #(throw e))

(lc/defword crashed [id & insts]
  (-> [(check #{[:transferred id]})]
    (into insts)
    (conj (change throwing))))

(defn detect [id x]
  (lc/event [:detected id x]))

(lc/defword detected [id & insts]
  (into [(lc/copy 0) (change peek) (swap) (change pop) (check #{[:detected id]})] insts))

(defn effect [l r]
  (lc/event
    [:performed
     (fn
       ([flag] ((if flag r l)))
       ([flag value] ((if flag r l) value)))]))

(lc/defword performed [id & insts]
  (-> [(bi peek pop)
       (check #{[:performed]})
       (insert id)]
    (into insts)
    (conj (->It id))))

(defn flow [id]
  (fn [n t] (lc/event [:spawned id #((if % t n))])))

(lc/defword cancelled [id & insts]
  (-> [(check #{[:cancelled id]})]
    (into insts)
    (conj nil)))

(lc/defword store [& insts]
  (-> [{}]
    (into insts)
    (conj (lc/drop 0))))

(defn run [& insts]
  (assert (= [] (lc/run (apply store insts)))))

;; TASKS

(lc/defword start [id & events]
  [(fn [v] (lc/event [:succeeded id v])) (swap)
   (fn [v] (lc/event [:failed id v])) (swap)
   (apply lc/call 2 events)
   (insert id)])

(lc/defword succeeded [id pred] [(bi peek pop) (check #{[:succeeded id]}) (check pred) nil])
(lc/defword failed [id pred] [(bi peek pop) (check #{[:failed id]}) (check pred) nil])

(defn task [id]
  (fn [s f] (lc/event [:started id #((case % :succeed s :fail f) %2)])))

(lc/defword started [id & insts]
  (-> [(bi peek pop) (check #{[:started id]}) (insert id)]
    (into insts)
    (conj (->It id))))

(lc/defword succeed [id v & events]
  (-> [(lookup id) :succeed (swap)]
    (conj v (swap) (apply lc/call 2 events) (lose))))

(lc/defword fail [id v & events]
  (-> [(lookup id) :fail (swap)]
    (conj v (swap) (apply lc/call 2 events) (lose))))
