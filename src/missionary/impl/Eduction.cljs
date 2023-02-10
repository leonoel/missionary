(ns missionary.impl.Eduction)

(declare cancel transfer)
(deftype Process
  [reducer iterator
   notifier terminator
   buffer
   ^number offset
   ^number length
   ^number error
   ^boolean busy
   ^boolean done]
  IFn
  (-invoke [t] (cancel t))
  IDeref
  (-deref [t] (transfer t)))

(defn feed
  ([^Process t] t)
  ([^Process t x]
   (if (== (.-length t) (alength (.-buffer t)))
     (.push (.-buffer t) x)
     (aset (.-buffer t) (.-length t) x))
   (set! (.-length t) (inc (.-length t))) t))

(defn pull [^Process t]
  (loop []
    (if (.-done t)
      (if-some [rf (.-reducer t)]
        (do (set! (.-offset t) 0)
            (set! (.-length t) 0)
            (try (rf t)
                 (catch :default e
                   (set! (.-error t) (.-length t))
                   (feed t e)))
            (set! (.-reducer t) nil)
            (if (zero? (.-length t))
              (recur)
              (do ((.-notifier t))
                  (when (set! (.-busy t) (not (.-busy t))) (recur)))))
        ((.-terminator t)))
      (if-some [rf (.-reducer t)]
        (do (set! (.-offset t) 0)
            (set! (.-length t) 0)
            (try (when (reduced? (rf t @(.-iterator t)))
                   (rf t)
                   (set! (.-reducer t) nil)
                   (cancel t))
                 (catch :default e
                   (set! (.-error t) (.-length t))
                   (feed t e)
                   (set! (.-reducer t) nil)
                   (cancel t)))
            (if (pos? (.-length t))
              ((.-notifier t))
              (when (set! (.-busy t) (not (.-busy t))) (recur))))
        (do (try @(.-iterator t) (catch :default _))
            (when (set! (.-busy t) (not (.-busy t))) (recur)))))))

(defn cancel [^Process t]
  ((.-iterator t)))

(defn transfer [^Process t]
  (let [o (.-offset t)
        x (aget (.-buffer t) o)]
    (aset (.-buffer t) o nil)
    (set! (.-offset t) (inc o))
    (if (== (.-offset t) (.-length t))
      (when (set! (.-busy t) (not (.-busy t)))
        (pull t)) ((.-notifier t)))
    (if (== o (.-error t)) (throw x) x)))

(defn run [xf flow n t]
  (let [t (->Process (xf feed) nil n t (object-array 1) 0 0 -1 true false)
        n #(when (set! (.-busy t) (not (.-busy t))) (pull t))]
    (set! (.-iterator t) (flow n #(do (set! (.-done t) true) (n))))
    (n) t))