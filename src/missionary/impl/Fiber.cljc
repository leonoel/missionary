(ns ^:no-doc missionary.impl.Fiber)

(defprotocol Fiber
  (park [_ t])
  (swich [_ f])
  (fork [_ p f])
  (check [_])
  (unpark [_]))

(deftype Default []
  Fiber
  (park [_ _] (throw (#?(:cljs js/Error. :cljd Exception.) "Unsupported operation.")))
  (swich [_ _] (throw (#?(:cljs js/Error. :cljd Exception.) "Unsupported operation.")))
  (fork [_ _ _] (throw (#?(:cljs js/Error. :cljd Exception.) "Unsupported operation.")))
  (check [_] (throw (#?(:cljs js/Error. :cljd Exception.) "Unsupported operation.")))
  (unpark [_] (throw (#?(:cljs js/Error. :cljd Exception.) "Unsupported operation."))))

(def fiber (->Default))

(defn current [] fiber)
