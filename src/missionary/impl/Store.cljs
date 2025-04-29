(ns missionary.impl.Store
  (:import missionary.Cancelled))

(declare freeze append spawn cancel transfer)

(deftype Call [done thunk]
  IFn
  (-invoke [_])
  IDeref
  (-deref [_]
    (done) (thunk)))

(deftype Frozen [failed result]
  IFn
  (-invoke [_]
    (if ^boolean failed
      (throw result) result)))

(deftype Port [sg state]
  IFn
  (-invoke [this]
    (freeze this))
  (-invoke [this x]
    (append this x))
  (-invoke [this step done]
    (spawn this step done)))

(deftype Reader [port step done state last]
  IFn
  (-invoke [this]
    (cancel this))
  IDeref
  (-deref [this]
    (transfer this)))

(def over (js-obj))
(def zero (js-obj))

(defn concurrent []
  (throw (js/Error. "Concurrent store reader.")))

(defn terminate [^Reader reader last]
  (let [^Port port (.-port reader)]
    (set! (.-state port) last)
    (set! (.-last reader) zero)))

(defn freeze [^Port port]
  (let [s (.-state port)]
    (if (instance? Reader s)
      (let [^Reader reader s
            t (.-state reader)]
        (if (identical? t zero)
          (do (set! (.-state reader) over)
              (terminate reader (->Frozen false (.-last reader)))
              ((.-done reader)))
          (when-not (instance? Frozen t)
            (set! (.-state reader) (->Frozen false t)))))
      (when-not (instance? Frozen s)
        (set! (.-state port) (->Frozen false s))))))

(defn collapse [^Port port x y]
  (try ((.-sg port) x y)
       (catch :default e
         (->Frozen true e))))

(defn append [^Port port x]
  (let [s (.-state port)]
    (if (instance? Reader s)
      (let [^Reader reader s
            t (.-state reader)]
        (if (identical? t zero)
          (do (set! (.-state reader) x)
              ((.-step reader)))
          (when-not (instance? Frozen t)
            (set! (.-state reader) (collapse port t x)))))
      (when-not (instance? Frozen s)
        (set! (.-state port) (collapse port s x))))))

(defn accumulate [^Reader reader y]
  (let [^Port port (.-port reader)
        x (.-last reader)]
    (if (identical? zero x)
      y ((.-sg port) x y))))

(defn cancel [^Reader reader]
  (let [t (.-state reader)]
    (when-not (identical? over t)
      (set! (.-state reader) over)
      (if (identical? zero t)
        (do (terminate reader (.-last reader))
            ((.-step reader)))
        (try (terminate reader
               (if (instance? Frozen t)
                 (->Frozen false (accumulate reader (t)))
                 (accumulate reader t)))
             (catch :default e
               (terminate reader
                 (->Frozen true e))))))))

(defn transfer [^Reader reader]
  (let [t (.-state reader)]
    (if (identical? over t)
      (do ((.-done reader))
          (throw (Cancelled. "Store reader cancelled.")))
      (try (if (instance? Frozen t)
             (let [x (t)
                   r (accumulate reader x)]
               (set! (.-state reader) over)
               (terminate reader (->Frozen false r))
               ((.-done reader)) x)
             (let [r (accumulate reader t)]
               (set! (.-state reader) zero)
               (set! (.-last reader) r) t))
           (catch :default e
             (set! (.-state reader) over)
             (terminate reader (->Frozen true e))
             ((.-done reader)) (throw e))))))

(defn spawn [^Port port step done]
  (step)
  (let [s (.-state port)]
    (if (instance? Reader s)
      (->Call done concurrent)
      (if (instance? Frozen s)
        (->Call done s)
        (set! (.-state port) (->Reader port step done s zero))))))

(def make ->Port)