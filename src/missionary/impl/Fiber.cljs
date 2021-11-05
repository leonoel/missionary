(ns ^:no-doc missionary.impl.Fiber)

(defprotocol Fiber
  (park [_ t])
  (swich [_ f])
  (fork [_ p f])
  (check [_])
  (unpark [_]))

(deftype Default []
  Fiber
  (park [_ _] (throw (js/Error. "Unsupported operation.")))
  (swich [_ _] (throw (js/Error. "Unsupported operation.")))
  (fork [_ _ _] (throw (js/Error. "Unsupported operation.")))
  (check [_] (throw (js/Error. "Unsupported operation.")))
  (unpark [_] (throw (js/Error. "Unsupported operation."))))

(def fiber (->Default))

(defn current [] fiber)