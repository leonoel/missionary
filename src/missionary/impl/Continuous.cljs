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
  [^Process process prev next child sibling backtrack iterator value ^boolean busy ^boolean done ^number rank])

(deftype Flow [cr]
  IFn
  (-invoke [_ n t]
    (n) (->Process n t cr nil nil true 0)))

(defn top [^Choice ch]
  (when-not (.-live (.-process ch))
    ((.-iterator ch))))

(defn detach [^Choice ch]
  (let [ps (.-process ch)
        p (.-prev ch)]
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
    (set! (.-next ch) nil)))

(defn link [^Choice x ^Choice y]
  (if (< (.-rank x) (.-rank y))
    (do (set! (.-sibling y) (.-child x))
        (set! (.-child x) y) x)
    (do (set! (.-sibling x) (.-child y))
        (set! (.-child y) x) y)))

(defn prune [^Choice ch]
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
                     (if (identical? r ps)
                       (recur (set! (.-backtrack (.-choice ps)) cr)) r)))
                 (catch :default e
                   (when-some [ch (.-choice ps)]
                     (let [ch (.-next ch)]
                       (prune ch)
                       (set! (.-prev ch) nil)
                       (set! (.-next ch) nil)
                       (set! (.-choice ps) nil)
                       ((.-iterator ch))))
                   (set! (.-notifier ps) nil) e))]
      (set! fiber f) r)))

(defn pull [^Choice ch x]
  (let [ps (.-process ch)
        bt (.-backtrack ch)]
    (try
      (loop []
        (let [r @(.-iterator ch)]
          (if (.-busy ch)
            (if (= (.-value ch) (set! (.-value ch) r))
              (do (set! (.-busy ch) false) x)
              (do (prune ch) (step ps bt)))
            (if (.-done ch)
              (if (= (.-value ch) (set! (.-value ch) r))
                (do (set! (.-pending ps) (dec (.-pending ps)))
                    (detach ch) x)
                (do (prune ch) (step ps bt)))
              (do (set! (.-busy ch) true) (recur))))))
      (catch :default e
        (set! (.-iterator ch) nil)
        (set! (.-value ch) e)
        (prune ch)
        (step ps bt)))))

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

(defn ready [^Choice ch]
  (let [ps (.-process ch)]
    (loop []
      (when (set! (.-busy ch) (not (.-busy ch)))
        (if (.-done ch)
          (do (when-not (nil? (.-prev ch)) (detach ch))
              (when (zero? (set! (.-pending ps) (dec (.-pending ps))))
                (when-not (identical? ps (.-value ps)) (.-terminator ps))))
          (if (nil? (.-prev ch))
            (do (try @(.-iterator ch) (catch :default _)) (recur))
            (if-some [d (.-dirty ps)]
              (do (set! (.-dirty ps) (link d ch)) nil)
              (do (set! (.-dirty ps) ch)
                  (when-not (identical? ps (.-value ps))
                    (.-notifier ps))))))))))

(defn suspend [ps f]
  (let [ch (->Choice ps nil nil nil nil nil nil ps true false 0)]
    (set! (.-iterator ch)
      (f #(when-some [cb (ready ch)] (cb))
        #(do (set! (.-done ch) true)
             (when-some [cb (ready ch)] (cb)))))
    (if-some [p (.-choice ps)]
      (let [n (.-next p)]
        (set! (.-prev ch) p)
        (set! (.-next ch) n)
        (set! (.-next p) ch)
        (set! (.-prev n) ch)
        (set! (.-rank ch)
          (inc (.-rank p))))
      (do (set! (.-prev ch) ch)
          (set! (.-next ch) ch)
          (top ch)))
    (set! (.-choice ps) ch)
    (set! (.-pending ps)
      (inc (.-pending ps))) ps))

(defn kill [^Process ps]
  (when (.-live ps)
    (set! (.-live ps) false)
    (when-some [ch (.-choice ps)]
      ((.-iterator (.-next ch))))))

(defn push [^Choice ch]
  (let [ps (.-process ch)
        x (.-value ch)]
    (if (identical? x ps)
      (if (set! (.-busy ch) (not (.-busy ch)))
        (if (.-done ch)
          (do (set! (.-pending ps) (dec (.-pending ps))) (detach ch)
              (throw (js/Error. "Undefined continuous flow.")))
          (try
            (set! (.-value ch)
              (loop []
                (let [x @(.-iterator ch)]
                  (if (.-busy ch)
                    (do (set! (.-busy ch) false) x)
                    (if (.-done ch)
                      (do (set! (.-pending ps) (dec (.-pending ps)))
                          (detach ch) x)
                      (do (set! (.-busy ch) true)
                          (recur)))))))
            (catch :default e
              (if (.-done ch)
                (do (set! (.-pending ps) (dec (.-pending ps)))
                    (detach ch)) (set! (.-busy ch) false))
              (throw e))))
        (do (detach ch) ((.-iterator ch))
            (throw (js/Error. "Undefined continuous flow."))))
      (do (if (.-done ch)
            (do (set! (.-pending ps) (dec (.-pending ps)))
                (detach ch)) (set! (.-busy ch) false))
          (if (nil? (.-iterator ch)) (throw x) x)))))

(defn transfer [^Process ps]
  (let [x (.-value ps)]
    (set! (.-value ps) ps)
    (loop [x (if-some [d (.-dirty ps)]
               (do (set! (.-dirty ps) (dequeue d))
                   (pull d x))
               (step ps x))]
      (if-some [d (.-dirty ps)]
        (do (set! (.-dirty ps) (dequeue d))
            (recur (if (nil? (.-prev d))
                     (do (try @(.-iterator d)
                              (catch :default _))
                         (ready d) x)
                     (pull d x))))
        (do (set! (.-value ps) x)
            (when (zero? (.-pending ps))
              ((.-terminator ps)))
            (if (nil? (.-notifier ps))
              (throw x) x))))))

(def flow ->Flow)