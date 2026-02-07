(ns missionary.Cancelled)

(deftype Cancelled [message]
  #?@(:cljd [Object
             (toString [_] (str "Cancelled: " message))]))

