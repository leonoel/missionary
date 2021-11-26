(ns missionary.impl.Relieve)

(declare transfer)
(deftype Process
  [reducer notifier terminator
   iterator current last error
   ^boolean busy]
  IFn
  (-invoke [_] (iterator))
  IDeref
  (-deref [p] (transfer p)))

(defn nop [])

(defn transfer [^Process p]
  (let [x (.-current p)]
    (if (identical? nop x)
      (let [x (.-last p)
            e (.-error p)]
        (if (identical? nop x)
          (do ((.-terminator p)) (throw e))
          (do (set! (.-last p) nop)
              ((if (identical? nop e)
                 (.-terminator p) (.-notifier p))) x)))
      (do (set! (.-current p) nop) x))))

(defn pull [^Process p]
  (loop [s nop]
    (if (set! (.-busy p) (not (.-busy p)))
      (let [c (.-current p)]
        (recur
          (if-some [rf (.-reducer p)]
            (try (let [x @(.-iterator p)]
                   (if (identical? nop c)
                     (do (set! (.-current p) x) (.-notifier p))
                     (do (set! (.-current p) (rf c x)) s)))
                 (catch :default e
                   (set! (.-error p) e)
                   ((.-iterator p)) s))
            (do (set! (.-last p) c)
                (set! (.-current p) nop)
                (if (identical? nop c)
                  (if (identical? nop (.-error p))
                    (.-terminator p) (.-notifier p))
                  s))))) (s))))

(defn run [rf f n t]
  (let [p (->Process rf n t nil nop nop nop true)]
    (set! (.-iterator p) (f #(pull p) #(do (set! (.-reducer p) nil) (pull p))))
    (pull p) p))