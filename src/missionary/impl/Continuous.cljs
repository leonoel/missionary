(ns ^:no-doc missionary.impl.Continuous
  (:require [missionary.impl.Fiber :refer [Fiber fiber]])
  (:import missionary.Cancelled))

(declare kill transfer suspend attach cancel push)

(deftype Process
  [notifier terminator value choice dirty ^boolean live ^number pending]
  IFn
  (-invoke [this] (kill this) nil)
  IDeref
  (-deref [this] (transfer this))
  Fiber
  (park [_ _] (throw (js/Error. "Unsupported operation.")))
  (fork [_ _ _] (throw (js/Error. "Unsupported operation.")))
  (swich [this flow] (attach this flow))
  (check [_] (when-not live (throw (Cancelled. "Process cancelled."))))
  (unpark [_] (push choice)))

(deftype Choice
  [^Process process prev next child sibling backtrack iterator value ^boolean busy ^boolean done ^number rank]
  Fiber
  (park [_ _] (throw (js/Error. "Unsupported operation.")))
  (fork [_ _ _] (throw (js/Error. "Unsupported operation.")))
  (swich [_ flow] (cancel process flow))
  (check [_] (throw (Cancelled. "Process cancelled.")))
  (unpark [this] (push this)))

(defn resume [c ^Process ps]
  (set! (.-value ps) c) nil)

(defn top [^Choice ch]
  (when-not (.-live (.-process ch))
    ((.-iterator ch))))

(defn done [^Choice ch]
  (let [ps (.-process ch)]
    (set! (.-pending ps) (dec (.-pending ps)))
    (when-some [p (.-prev ch)]
      (set! (.-prev ch) nil)
      (if (identical? ch p)
        (set! (.-choice ps) nil)
        (let [n (.-next ch)]
          (set! (.-prev n) p)
          (set! (.-next p) n)
          (if (identical? ch (.-choice ps))
            (set! (.-choice ps) p)
            (when (identical? p (.-choice ps))
              (top n)))))
      (set! (.-next ch) nil))))

(defn link [^Choice x ^Choice y]
  (if (< (.-rank x) (.-rank y))
    (do (set! (.-sibling y) (.-child x))
        (set! (.-child x) y) x)
    (do (set! (.-sibling x) (.-child y))
        (set! (.-child y) x) y)))

(defn pull [^Choice ch]
  (let [p (.-value ch)]
    (set! (.-value ch)
      (try
        (loop []
          (let [x @(.-iterator ch)]
            (if (.-busy ch)
              x (if (.-done ch)
                  x (do (set! (.-busy ch) true)
                        (recur))))))
        (catch :default e
          (set! (.-iterator ch) nil)
          e))) p))

(defn detach [^Choice ch]
  (let [ps (.-process ch)]
    (loop []
      (let [c (.-choice ps)]
        (when-not (identical? c ch)
          (let [p (.-prev c)
                n (.-next c)]
            (set! (.-prev c) nil)
            (set! (.-next c) nil)
            (set! (.-next p) n)
            (set! (.-prev n) p)
            (set! (.-choice ps) p)
            ((.-iterator c))
            (recur)))))))

(defn step [^Process ps]
  (let [f fiber]
    (set! fiber ps)
    (set! (.-value ps)
      (try (loop []
             (let [x ((.-value ps))]
               (if (identical? x ps)
                 (recur) x)))
           (catch :default e
             (when-some [ch (.-choice ps)]
               (let [ch (.-next ch)]
                 (detach ch)
                 (set! (.-prev ch) nil)
                 (set! (.-next ch) nil)
                 (set! (.-choice ps) nil)
                 ((.-iterator ch))))
             (set! (.-notifier ps) nil) e)))
    (set! fiber f)))

(defn dequeue [^Choice c]
  (let [head (.-child c)]
    (set! (.-child c) nil)
    (loop [heap nil
           prev nil
           head head]
      (if (nil? head)
        (if (nil? prev) heap (if (nil? heap) prev (link heap prev)))
        (let [next (.-sibling head)]
          (set! (.-sibling head) nil)
          (if (nil? prev)
            (recur heap head next)
            (let [head (link prev head)]
              (recur (if (nil? heap) head (link heap head)) nil next))))))))

(defn discard [^Choice ch]
  (pull ch)
  (let [ps (.-process ch)
        p (.-value ps)
        f fiber]
    (set! fiber ch)
    ((.-backtrack ch) resume ps)
    (try ((.-value ps)) (catch :default _))
    (set! (.-value ps) p)
    (set! fiber f)))

(defn barrier [^Process ps]
  (when (zero? (.-pending ps))
    (.-terminator ps)))

(defn ready [^Choice ch]
  (let [ps (.-process ch)]
    (when (set! (.-busy ch) (not (.-busy ch)))
      (if (.-done ch)
        (do (done ch) (barrier ps))
        (if (nil? (.-prev ch))
          (do (discard ch) (barrier ps))
          (if-some [d (.-dirty ps)]
            (do (set! (.-dirty ps) (link d ch)) nil)
            (do (set! (.-dirty ps) ch) (.-notifier ps))))))))

(defn suspend [ps f]
  (let [ch (->Choice ps nil nil nil nil (.-value ps) nil nil true false 0)]
    (set! (.-iterator ch)
      (f #(when-some [cb (ready ch)] (cb))
        #(do (set! (.-done ch) true)
             (when-some [cb (ready ch)] (cb)))))
    (set! (.-pending ps) (inc (.-pending ps)))
    (if (set! (.-busy ch) (not (.-busy ch)))
      (if (.-done ch)
        (do (done ch)
            (throw (js/Error. "Undefined continuous flow.")))
        ch) (do ((.-iterator ch))
                (throw (js/Error. "Undefined continuous flow."))))))

(defn kill [^Process ps]
  (when (.-live ps)
    (set! (.-live ps) false)
    (when-some [ch (.-choice ps)]
      ((.-iterator (.-next ch))))))

(defn ack [^Choice ch]
  (if (.-done ch)
    (done ch)
    (set! (.-busy ch) false)))

(defn push [^Choice ch]
  (ack ch)
  (if (nil? (.-iterator ch))
    (throw (.-value ch))
    (.-value ch)))

(defn transfer [^Process ps]
  (set! (.-pending ps) (inc (.-pending ps)))
  (if-some [d (.-dirty ps)]
    (loop [d d]
      (set! (.-dirty ps) (dequeue d))
      (if (nil? (.-prev d))
        (discard d)
        (if (and (= (pull d) (.-value d))
              (some? (.-iterator d)))
          (ack d)
          (do ((.-backtrack d) resume ps)
              (detach d) (step ps))))
      (when-some [d (.-dirty ps)]
        (recur d)))
    (step ps))
  (set! (.-pending ps) (dec (.-pending ps)))
  (let [x (.-value ps)]
    (when-some [cb (barrier ps)] (cb))
    (if (nil? (.-notifier ps))
      (throw x) x)))

(defn attach [^Process ps flow]
  (let [ch (suspend ps flow)
        p (.-choice ps)]
    (set! (.-choice ps) ch)
    (if (nil? p)
      (do (set! (.-prev ch) ch)
          (set! (.-next ch) ch)
          (top ch))
      (let [n (.-next p)]
        (set! (.-prev ch) p)
        (set! (.-next ch) n)
        (set! (.-next p) ch)
        (set! (.-prev n) ch)
        (set! (.-rank ch)
          (inc (.-rank p)))))
    ((.-backtrack ch) resume ps)
    (pull ch) ps))

(defn cancel [^Process ps flow]
  (let [ch (suspend ps flow)]
    ((.-iterator ch))
    (discard ch) nil))

(defn run [cr n t]
  (n) (->Process n t cr nil nil true 0))