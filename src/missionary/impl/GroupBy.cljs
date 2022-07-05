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
  (-invoke [p] (kill p) nil)
  IDeref
  (-deref [p] (sample p)))

(deftype Group [process key notifier terminator]
  IFn
  (-invoke [g] (cancel g) nil)
  IDeref
  (-deref [g] (consume g)))

(defn kill [^Process p]
  (when (.-live p)
    (set! (.-live p) false)
    ((.-input p))))

(defn step [^number i ^number m]
  (bit-and (inc i) m))

(defn insert [^Process p ^number i g]
  (let [table (.-table p)
        ts (alength table)
        ls (bit-shift-left ts 1)]
    (aset table i g)
    (when (<= ls (* 3 (set! (.-load p) (inc (.-load p)))))
      (let [l (set! (.-table p) (object-array ls))
            m (dec ls)]
        (loop [j 0]
          (when (< j ts)
            (when-some [h (aget table j)]
              (loop [i (bit-and (hash (.-key h)) m)]
                (if (nil? (aget l i))
                  (aset l i h)
                  (recur (step i m)))))
            (recur (inc j))))))))

(defn group [^Process p k n t]
  (let [g (->Group nil k n t)]
    ((if-some [table (.-table p)]
       (let [m (dec (alength table))]
         (loop [i (bit-and (hash k) m)]
           (if-some [h (aget table i)]
             (when-not (= k (.-key h))
               (recur (step i m)))
             (when (= k (.-key p))
               (insert (set! (.-process g) p) i g))))
         n) t)) g))

(defn delete [^Process p ^number i ^number m]
  (set! (.-load p) (dec (.-load p)))
  (let [table (.-table p)]
    (loop [j i]
      (aset table j nil)
      (let [i (step j m)]
        (when-some [h (aget table i)]
          (when-not (== i (bit-and (hash (.-key h)) m))
            (aset table j h)
            (recur i)))))))

(defn cancel [^Group g]
  (when-some [^Process p (.-process g)]
    (when (.-live p)
      (set! (.-process g) nil)
      (let [k (.-key g)
            table (.-table p)
            m (dec (alength table))]
        (loop [i (bit-and (hash k) m)]
          (if (identical? (aget table i) g)
            (do (delete p i m)
                ((if (= k (.-key p))
                   (.-notifier p)
                   (.-notifier g))))
            (recur (step i m))))))))

(defn transfer [^Process p]
  (when-some [cb (loop []
                   (when (set! (.-busy p) (not (.-busy p)))
                     (if (.-done p)
                       (.-terminator p)
                       (if (identical? p (.-value p))
                         (try
                           (let [k (set! (.-key p) ((.-keyfn p) (set! (.-value p) @(.-input p))))
                                 table (.-table p)
                                 m (dec (alength table))]
                             (loop [i (bit-and (hash k) m)]
                               (if-some [h (aget table i)]
                                 (if (= k (.-key h))
                                   (.-notifier h)
                                   (recur (step i m)))
                                 (.-notifier p))))
                           (catch :default e
                             ((.-input p))
                             (set! (.-value p) e)
                             (.-notifier p)))
                         (do (try @(.-input p) (catch :default _))
                             (recur))))))] (cb)))

(defn sample [^Process p]
  (let [k (.-key p)]
    (if (identical? k p)
      (do (transfer p) (throw (.-value p)))
      (->MapEntry k (partial group p k) nil))))

(defn consume [^Group g]
  (if-some [^Process p (.-process g)]
    (let [x (.-value p)]
      (set! (.-value p) p)
      (set! (.-key p) p)
      (transfer p) x)
    (do ((.-terminator g))
        (throw (Cancelled. "Group consumer cancelled.")))))

(defn run [k f n t]
  (let [p (->Process k n nil nil nil nil (object-array 8) 0 true true false)]
    (set! (.-terminator p)
      (fn []
        (let [table (.-table p)]
          (loop [i 0]
            (when (< i (alength table))
              (when-some [g (aget table i)]
                (set! (.-process g) nil)
                ((.-terminator g)))
              (recur (inc i))))
          (set! (.-table p) nil) (t))))
    (set! (.-key p) p)
    (set! (.-value p) p)
    (set! (.-input p)
      (f #(transfer p)
        #(do (set! (.-done p) true)
             (transfer p))))
    (transfer p) p))
