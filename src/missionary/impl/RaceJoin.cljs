(ns missionary.impl.RaceJoin)

(declare cancel)
(deftype Process
  [combinator
   joincb racecb
   children result
   ^number join
   ^number race]
  IFn
  (-invoke [j] (cancel j)))

(defn cancel [^Process j]
  (dotimes [i (alength (.-children j))]
    ((aget (.-children j) i))))

(defn terminated [^Process j]
  (let [n (inc (.-join j))]
    (set! (.-join j) n)
    (when (== n (alength (.-result j)))
      (let [w (.-race j)]
        (if (neg? w)
          (try ((.-joincb j) (.apply (.-combinator j) nil (.-result j)))
               (catch :default e ((.-racecb j) e)))
          ((.-racecb j) (aget (.-result j) w)))))))

(defn run [r c ts s f]
  (let [n (count ts)
        i (iter ts)
        j (->Process c (if r f s) (if r s f) (object-array n) (object-array n) 0 -2)]
    (loop [index 0]
      (let [join (fn [x]
                   (aset (.-result j) index x)
                   (terminated j))
            race (fn [x]
                   (let [w (.-race j)]
                     (when (neg? w)
                       (set! (.-race j) index)
                       (when (== -1 w) (cancel j))))
                   (join x))]
        (aset (.-children j) index ((.next i) (if r race join) (if r join race)))
        (when (.hasNext i) (recur (inc index)))))
    (if (== -2 (.-race j))
      (set! (.-race j) -1)
      (cancel j)) j))