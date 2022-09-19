(ns ^:no-doc missionary.impl.Continuous
  (:require [missionary.impl.Fiber :refer [Fiber fiber]])
  (:import missionary.Cancelled))

(declare kill transfer suspend push)

(deftype Process
  [notifier terminator value choice dirty ^boolean live ^number pending]
  IFn
  (-invoke [this] (kill this) nil)
  IDeref
  (-deref [this] (transfer this))
  Fiber
  (park [_ _] (throw (js/Error. "Unsupported operation.")))
  (fork [_ _ _] (throw (js/Error. "Unsupported operation.")))
  (swich [this flow] (suspend this flow))
  (check [_] (when-not live (throw (Cancelled. "Process cancelled."))))
  (unpark [_] (push choice)))

(deftype Choice
  [^Process process prev next child sibling backtrack iterator value ^boolean busy ^boolean done ^number rank]
  Fiber
  (park [_ _] (throw (js/Error. "Unsupported operation.")))
  (fork [_ _ _] (throw (js/Error. "Unsupported operation.")))
  (swich [_ flow] (suspend process flow))
  (check [_] (throw (Cancelled. "Process cancelled.")))
  (unpark [this] (push this)))

(deftype Flow [cr]
  IFn
  (-invoke [_ n t]
    (n) (->Process n t cr nil nil true 0)))

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

(defn step [^Process ps cr]
  (let [f fiber]
    (set! fiber ps)
    (let [r (try (loop [cr cr]
                   (let [cr (cr identity)
                         r (cr)]
                     (if (instance? Choice r)
                       (let [ch ^Choice r
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
                         (set! (.-backtrack ch) cr)
                         (recur cr)) r)))
                 (catch :default e
                   (when-some [ch (.-choice ps)]
                     (let [ch (.-next ch)]
                       (detach ch)
                       (set! (.-prev ch) nil)
                       (set! (.-next ch) nil)
                       (set! (.-choice ps) nil)
                       ((.-iterator ch))))
                   (set! (.-notifier ps) nil) e))]
      (set! fiber f) r)))

(defn resume [^Choice ch x]
  (let [cr (.-backtrack ch)]
    (if (nil? (.-prev ch))
      (let [f fiber]
        (set! fiber ch)
        (try (loop [cr cr]
               (let [cr (cr identity)
                     r (cr)]
                 (if (instance? Choice r)
                   (let [ch ^Choice r]
                     (set! (.-backtrack ch) cr)
                     ((.-iterator ch))
                     (set! fiber ch)
                     (recur cr)) r)))
             (catch :default _))
        (set! fiber f) x)
      (do (detach ch)
          (step (.-process ch) cr)))))

(defn ack [^Choice ch]
  (if (.-done ch)
    (done ch)
    (set! (.-busy ch) false)))

(defn pull [^Choice ch x]
  (try
    (if (= (.-value ch)
          (set! (.-value ch)
            (loop []
              (let [r @(.-iterator ch)]
                (if (.-busy ch)
                  r (if (.-done ch)
                      r (do (set! (.-busy ch) true)
                            (recur))))))))
      (do (ack ch) x) (resume ch x))
    (catch :default e
      (set! (.-iterator ch) nil)
      (set! (.-value ch) e)
      (resume ch x))))

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

(defn barrier [^Process ps]
  (when (zero? (.-pending ps))
    (.-terminator ps)))

(defn ready [^Choice ch]
  (let [ps (.-process ch)]
    (when (set! (.-busy ch) (not (.-busy ch)))
      (if (.-done ch)
        (do (done ch)
            (when-not (identical? ps (.-value ps))
              (barrier ps)))
        (if (nil? (.-prev ch))
          (do (pull ch nil)
              (when-not (identical? ps (.-value ps))
                (barrier ps)))
          (if-some [d (.-dirty ps)]
            (do (set! (.-dirty ps) (link d ch)) nil)
            (do (set! (.-dirty ps) ch)
                (when-not (identical? ps (.-value ps))
                  (.-notifier ps)))))))))

(defn suspend [ps f]
  (let [ch (->Choice ps nil nil nil nil nil nil nil true false 0)]
    (set! (.-iterator ch)
      (f #(when-some [cb (ready ch)] (cb))
        #(do (set! (.-done ch) true)
             (when-some [cb (ready ch)] (cb)))))
    (set! (.-pending ps) (inc (.-pending ps)))
    (if (set! (.-busy ch) (not (.-busy ch)))
      (if (.-done ch)
        (do (done ch)
            (throw (js/Error. "Undefined continuous flow.")))
        (try
          (set! (.-value ch)
            (loop []
              (let [r @(.-iterator ch)]
                (if (.-busy ch)
                  r (if (.-done ch)
                      r (do (set! (.-busy ch) true)
                            (recur)))))))
          (catch :default e
            (set! (.-iterator ch) nil)
            (set! (.-value ch) e))))
      (do ((.-iterator ch))
          (throw (js/Error. "Undefined continuous flow."))))
    ch))

(defn kill [^Process ps]
  (when (.-live ps)
    (set! (.-live ps) false)
    (when-some [ch (.-choice ps)]
      ((.-iterator (.-next ch))))))

(defn push [^Choice ch]
  (ack ch)
  (if (nil? (.-iterator ch))
    (throw (.-value ch))
    (.-value ch)))

(defn transfer [^Process ps]
  (let [x (.-value ps)]
    (set! (.-value ps) ps)
    (loop [x (if-some [d (.-dirty ps)]
               (do (set! (.-dirty ps) (dequeue d))
                   (pull d x))
               (step ps x))]
      (if-some [d (.-dirty ps)]
        (do (set! (.-dirty ps) (dequeue d))
            (recur (pull d x)))
        (do (set! (.-value ps) x)
            (when-some [cb (barrier ps)] (cb))
            (if (nil? (.-notifier ps))
              (throw x) x))))))

(def flow ->Flow)