(ns ^:no-doc missionary.impl.GroupBy
  (:import missionary.Cancelled))

(declare kill sample cancel consume)

(deftype Process [keyfn notifier terminator
                  key value input table
                  ^number load
                  ^boolean live
                  ^boolean busy
                  ^boolean done]
  IFn
  (-invoke [p] (kill p))
  IDeref
  (-deref [p] (sample p)))

(deftype Group [process key notifier terminator]
  IFn
  (-invoke [g] (cancel g))
  IDeref
  (-deref [g] (consume g)))

(defn kill [^Process p]
  (when (.-live p)
    (set! (.-live p) false)
    ((.-input p))))

(defn step [^number i ^number m]
  (if (== i m) 0 (inc i)))

(defn insert [^Process p ^number i g]
  (let [t (.-table p)
        ts (alength t)
        ls (bit-shift-left ts 1)]
    (aset t i g)
    (when (<= ls (* 3 (set! (.-load p) (inc (.-load p)))))
      (let [l (set! (.-table p) (object-array ls))
            m (dec ls)]
        (loop [j 0]
          (when (< j ts)
            (when-some [h (aget t j)]
              (loop [i (bit-and (hash (.-key h)) m)]
                (if (nil? (aget l i))
                  (aset l i h)
                  (recur (step i m)))))
            (recur (inc j))))))))

(defn group [^Process p k n t]
  (let [g (->Group nil k n t)]
    (if-some [t (.-table p)]
      (let [m (dec (alength t))]
        (loop [i (bit-and (hash k) m)]
          (if-some [h (aget t i)]
            (when-not (= k (.-key h))
              (recur (step i m)))
            (when (= k (.-key p))
              (insert (set! (.-process g) p) i g))))
        (n)) (t)) g))

(defn delete [^Process p ^number i ^number m]
  (set! (.-load p) (dec (.-load p)))
  (let [t (.-table p)]
    (loop [j i]
      (aset t j nil)
      (let [i (step j m)]
        (when-some [h (aget t i)]
          (when-not (== i (bit-and (hash (.-key h)) m))
            (aset t j h)
            (recur i)))))))

(defn cancel [^Group g]
  (when-some [^Process p (.-process g)]
    (when (.-live p)
      (set! (.-process g) nil)
      (let [k (.-key g)
            t (.-table p)
            m (dec (alength t))]
        (loop [i (bit-and (hash k) m)]
          (if (identical? (aget t i) g)
            (do (delete p i m)
                ((if (= k (.-key p))
                   (.-notifier p)
                   (.-notifier g))))
            (recur (step i m))))))))

(defn sample [^Process p]
  (let [k (.-key p)]
    (if (identical? k p)
      (throw (.-value p))
      (->MapEntry k (partial group p k) nil))))

(defn transfer [^Process p]
  (while
    (when (set! (.-busy p) (not (.-busy p)))
      (let [t (.-table p)]
        (if (.-done p)
          (do (loop [i 0]
                (when (< i (alength t))
                  (when-some [g (aget t i)]
                    (set! (.-process g) nil)
                    ((.-terminator g)))
                  (recur (inc i))))
              (set! (.-table p) nil)
              ((.-terminator p)) false)
          (if (identical? p (.-value p))
            (try
              (let [k (set! (.-key p) ((.-keyfn p) (set! (.-value p) @(.-input p))))
                    m (dec (alength t))]
                ((loop [i (bit-and (hash k) m)]
                   (if-some [h (aget t i)]
                     (if (= k (.-key h))
                       (.-notifier h)
                       (recur (step i m)))
                     (.-notifier p)))) false)
              (catch :default e
                ((.-input p))
                (set! (.-value p) e)
                ((.-notifier p))
                true))
            (do (try @(.-input p) (catch :default _)) true)))))))

(defn consume [^Group g]
  (if-some [^Process p (.-process g)]
    (let [x (.-value p)]
      (set! (.-value p) p)
      (set! (.-key p) p)
      (transfer p) x)
    (do ((.-terminator g))
        (throw (Cancelled. "Group consumer cancelled.")))))

(defn run [k f n t]
  (let [p (->Process k n t nil nil nil (object-array 8) 0 true true false)]
    (set! (.-key p) p)
    (set! (.-value p) p)
    (set! (.-input p)
      (f #(transfer p)
        #(do (set! (.-done p) true)
             (transfer p))))
    (transfer p) p))
