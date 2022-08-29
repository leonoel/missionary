(ns missionary.impl.Heap)

(defn create [^number cap]
  (doto (js/Array. (inc cap))
    (aset 0 0)))

(defn size [heap]
  (aget heap 0))

(defn enqueue [heap i]
  (let [j (inc (aget heap 0))]
    (aset heap 0 j)
    (aset heap j i)
    (loop [j j]
      (when-not (== 1 j)
        (let [p (bit-shift-right j 1)
              x (aget heap j)
              y (aget heap p)]
          (when-not (< y x)
            (aset heap p x)
            (aset heap j y)
            (recur p)))))))

(defn dequeue [heap]
  (let [s (aget heap 0)
        i (aget heap 1)]
    (aset heap 0 (dec s))
    (aset heap 1 (aget heap s))
    (loop [j 1]
      (let [l (bit-shift-left j 1)]
        (when (< l s)
          (let [x (aget heap j)
                y (aget heap l)
                r (inc l)]
            (if (< r s)
              (let [z (aget heap r)]
                (if (< y z)
                  (when (< z x)
                    (aset heap r x)
                    (aset heap j z)
                    (recur r))
                  (when (< y x)
                    (aset heap l x)
                    (aset heap j y)
                    (recur l))))
              (when (< y x)
                (aset heap l x)
                (aset heap j y)
                (recur l))))))) i))