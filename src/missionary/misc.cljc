(ns missionary.misc
  (:require [cloroutine.impl #?(:clj :refer :cljs :refer-macros) [safe]]))

(defn nop
  ([])
  ([_])
  ([_ _])
  ([_ _ _])
  ([_ _ _ & _]))

(defn compel [task]
  (fn [s! f!] (task s! f!) nop))

(defn send-rf [x !] (! x) x)

(defn call-rf [x !] (!) x)

(defn disj-nil [s x]
  (when-not (== 1 (count s)) (disj s x)))

(defn dissoc-nil [m x]
  (when-not (== 1 (count m)) (dissoc m x)))

(defn fold [s f t]
  (fn [s! f!] (t (comp s! s) (comp f! f))))

(defn swap [t]
  (fn [s! f!] (t f! s!)))

(defn throwing [e]
  (fn [& _] (throw e)))

(defn attempt [t]
  (fn [s _] (t (comp s constantly) (comp s throwing))))

(defn absolve [t]
  (fn [s f] (t (fn [a] (safe [e (s (a))] (f e))) nil)))

(def dfv-dereference-cancelled
  (ex-info "Dataflow variable dereference cancelled." {:task/cancelled :dfv-dereference}))

(def rdv-give-cancelled
  (ex-info "Rendez-vous give cancelled." {:task/cancelled :rdv-give}))

(def rdv-take-cancelled
  (ex-info "Rendez-vous take cancelled." {:task/cancelled :rdv-take}))

(def buf-dequeue-cancelled
  (ex-info "Buffer dequeue cancelled." {:task/cancelled :buf-dequeue}))

(def sem-acquire-cancelled
  (ex-info "Semaphore acquire cancelled." {:task/cancelled :sem-acquire}))

(defn race-failure [errors]
  (ex-info "Race failure." {::errors errors}))

(def sleep-cancelled
  (ex-info "Sleep cancelled." {:task/cancelled :sleep}))

(def process-cancelled
  (ex-info "Process cancelled." {:task/cancelled :process}))