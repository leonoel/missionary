(ns ^:no-doc missionary.impl.PairingHeap)

(deftype Impl [lt get-child set-child get-sibling set-sibling])

(def impl ->Impl)

(defn meld [^Impl impl inst x y]
  (if ((.-lt impl) inst x y)
    (do ((.-set-sibling impl) inst y ((.-get-child impl) inst x))
        ((.-set-child impl) inst x y) x)
    (do ((.-set-sibling impl) inst x ((.-get-child impl) inst y))
        ((.-set-child impl) inst y x) y)))

(defn dmin [^Impl impl inst x]
  (when-some [head ((.-get-child impl) inst x)]
    ((.-set-child impl) inst x nil)
    (loop [heap nil
           prev nil
           head head]
      (let [next ((.-get-sibling impl) inst head)]
        ((.-set-sibling impl) inst head nil)
        (if (nil? prev)
          (if (nil? next)
            (if (nil? head)
              heap (if (nil? heap) head (meld impl inst heap head)))
            (recur heap head next))
          (let [head (meld impl inst prev head)
                heap (if (nil? heap) head (meld impl inst heap head))]
            (if (nil? next) heap (recur heap nil next))))))))
