(ns missionary.impl.util)

(defn yield []
  #?(:clj (Thread/yield)))

(defn error [msg]
  (new #?(:clj Error :cljs js/Error) msg))
