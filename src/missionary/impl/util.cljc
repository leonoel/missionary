(ns missionary.impl.util
  (:refer-clojure :exclude [object-array])
  (:require [missionary.impl.varhandle :as v])
  #?(:clj (:import (clojure.lang IMeta IObj IFn)
                   (java.lang.reflect Array)
                   (java.util Arrays)
                   (jdk.internal.util ArraysSupport))))

#?(:clj
   (deftype MetaHolder [f m]
     IMeta
     (meta [_] m)
     IObj
     (withMeta [_ meta]
       (MetaHolder. f meta))
     IFn
     (invoke [_] (f))
     (invoke [_ a] (f a))
     (invoke [_ a b] (f a b))
     Object
     (toString [_]
       (.toString f))
     (hashCode [_]
       (.hashCode f))
     (equals [_ o]
       (.equals f o))
     Comparable
     (compareTo [_ o]
       (.compareTo ^Comparable f o)))
   :cljs
   (deftype MetaHolder [f m]
     IMeta
     (-meta [_] m)
     IWithMeta
     (-with-meta [_ meta]
       (MetaHolder. f meta))
     IFn
     (-invoke [_] (f))
     (-invoke [_ a] (f a))
     (-invoke [_ a b] (f a b))
     Object
     (toString [_]
       (.toString f))
     IEquiv
     (-equiv [_ o]
       (-equiv f o))
     IHash
     (-hash [_]
       (-hash f))
     IComparable
     (-compare [_ o]
       (-compare f o))))

(def meta-holder ->MetaHolder)

#?(:cljs
   (defn ints-lt [x y]
     (let [xl (alength x)
           yl (alength y)
           l (min xl yl)]
       (loop [i 0]
         (if (< i l)
           (let [xi (aget x i)
                 yi (aget y i)]
             (if (= xi yi)
               (recur (inc i))
               (< xi yi)))
           (< yl xl))))))

#?(:clj
   (defn ints-lt [^ints x ^ints y]
     (let [xl (alength x)
           yl (alength y)
           i (ArraysSupport/mismatch x y (min xl yl))]
       (if (neg? i)
         (< yl xl)
         (< (aget x i) (aget y i))))))

(defn sentinel []
  #?(:clj (Object.) :cljs (js-obj)))

(defn object-array [size]
  #?(:clj (Array/newInstance Object (int size))
     :cljs (js/Array. size)))

#?(:cljs
   (defn object-array [size]
     (js/Array. size)))

#?(:clj
   (defn object-array
     {:inline (fn [size] `(Array/newInstance Object (int ~size)))}
     [size] (Array/newInstance Object (int size))))

(defn thread []
  #?(:clj (Thread/currentThread)
     :cljs thread))

(defn yield []
  #?(:clj (Thread/yield)))

(defn error [msg]
  (new #?(:clj Error :cljs js/Error) msg))

(defn discard [ps]
  (try @ps (catch #?(:clj Throwable :cljs :default) _)))

(defn call [f ^objects a]
  #?(:clj
     (let [arity (alength a)]
       (case arity
         0 (f)
         1 (f (aget a 0))
         2 (f (aget a 0) (aget a 1))
         3 (f (aget a 0) (aget a 1) (aget a 2))
         4 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3))
         5 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4))
         6 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5))
         7 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6))
         8 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7))
         9 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8))
         10 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8) (aget a 9))
         11 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8) (aget a 9) (aget a 10))
         12 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8) (aget a 9) (aget a 10) (aget a 11))
         13 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8) (aget a 9) (aget a 10) (aget a 11) (aget a 12))
         14 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8) (aget a 9) (aget a 10) (aget a 11) (aget a 12) (aget a 13))
         15 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8) (aget a 9) (aget a 10) (aget a 11) (aget a 12) (aget a 13) (aget a 14))
         16 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8) (aget a 9) (aget a 10) (aget a 11) (aget a 12) (aget a 13) (aget a 14) (aget a 15))
         17 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8) (aget a 9) (aget a 10) (aget a 11) (aget a 12) (aget a 13) (aget a 14) (aget a 15) (aget a 16))
         18 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8) (aget a 9) (aget a 10) (aget a 11) (aget a 12) (aget a 13) (aget a 14) (aget a 15) (aget a 16) (aget a 17))
         19 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8) (aget a 9) (aget a 10) (aget a 11) (aget a 12) (aget a 13) (aget a 14) (aget a 15) (aget a 16) (aget a 17) (aget a 18))
         20 (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8) (aget a 9) (aget a 10) (aget a 11) (aget a 12) (aget a 13) (aget a 14) (aget a 15) (aget a 16) (aget a 17) (aget a 18) (aget a 19))
         (let [n (unchecked-subtract-int arity 20)
               r (object-array n)]
           (System/arraycopy a 20 r 0 n)
           (f (aget a 0) (aget a 1) (aget a 2) (aget a 3) (aget a 4) (aget a 5) (aget a 6) (aget a 7) (aget a 8) (aget a 9) (aget a 10) (aget a 11) (aget a 12) (aget a 13) (aget a 14) (aget a 15) (aget a 16) (aget a 17) (aget a 18) (aget a 19) r))))
     :cljs
     (.apply f nil a)))

(defn print-err [& args]
  #?(:clj
     (binding [*out* *err*] (apply println args))
     :cljs
     (.apply (.-error js/console) (into-array (map print-str args)))))

(defmacro swap-input [desc o i]
  `(let [o# ~o
         i# ~i]
     (loop []
       (if-some [t# (v/get-volatile ~desc o#)]
         (when-not (v/compare-and-set ~desc o# t# i#)
           (recur)) (i#)))))