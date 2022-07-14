(ns ^:no-doc missionary.impl.Continuous
  (:require [missionary.impl.Fiber :refer [Fiber fiber]])
  (:import missionary.Cancelled))

(declare kill transfer suspend sample ready)

(deftype Process [notifier terminator coroutine choice ^boolean live ^number pending]
  IFn
  (-invoke [this] (kill this) nil)
  IDeref
  (-deref [this] (transfer this))
  Fiber
  (park [_ _] (throw (js/Error. "Unsupported operation.")))
  (fork [_ _ _] (throw (js/Error. "Unsupported operation.")))
  (swich [this flow] (suspend this flow))
  (check [_] (when-not live (throw (Cancelled. "Process cancelled."))))
  (unpark [_] (sample choice)))

(deftype Choice [process prev next backtrack iterator ^boolean busy ^boolean done]
  Fiber
  (park [_ _] (throw (js/Error. "Unsupported operation.")))
  (fork [_ _ _] (throw (js/Error. "Unsupported operation.")))
  (swich [_ flow] (suspend process flow))
  (check [_] (throw (Cancelled. "Process cancelled.")))
  (unpark [this] (sample this)))

(defn getset [c ^Process ps]
  (let [cr (.-coroutine ps)]
    (set! (.-coroutine ps) c) cr))

(defn discard [cr ^Choice ch]
  (let [f fiber]
    (set! fiber ch)
    (try (let [x (cr)]
           (when (instance? Choice x)
             (let [ch ^Choice x]
               (set! (.-backtrack ch) cr)
               ((.-iterator ch))
               (ready ch))))
         (catch :default _))
    (set! fiber f) nil))

(defn kill [^Process ps]
  (when (.-live ps)
    (set! (.-live ps) false)
    (when-some [ch (.-choice ps)]
      ((.-iterator (.-next ch))))))

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

(defn sample [^Choice ch]
  (try
    (loop []
      (let [x @(.-iterator ch)]
        (if (set! (.-busy ch) (not (.-busy ch)))
          (if (.-done ch)
            (do (done ch) x)
            (recur)) x)))
    (catch :default e
      (when (set! (.-busy ch) (not (.-busy ch)))
        (when (.-done ch) (done ch)))
      (throw e))))

(defn detach [^Choice ch]
  (let [ps (.-process ch)]
    (loop []
      (let [c (.-choice ps)]
        (when-not (identical? c ch)
          (let [p (.-prev c)
                n (.-next c)]
            (set! (.-prev c) nil)
            (set! (.-next c) nil)
            (set! (.-choice ps) p)
            (set! (.-next p) n)
            (set! (.-prev n) p)
            ((.-iterator c))
            (recur)))))))

(defn ready [^Choice ch]
  (when (set! (.-busy ch) (not (.-busy ch)))
    (if (.-done ch)
      (done ch)
      (if (nil? (.-prev ch))
        ((.-backtrack ch) discard ch)
        (let [ps (.-process ch)
              cur (.-choice ps)]
          (detach ch)
          (when-some [cr ((.-backtrack ch) getset ps)]
            (discard cr cur)))))))

(defn transfer [^Process ps]
  (let [f fiber]
    (set! fiber ps)
    (let [x (try
              (loop []
                (let [x ((.-coroutine ps))]
                  (if (instance? Choice x)
                    (let [c ^Choice x]
                      (set! (.-backtrack c) ((.-coroutine ps) getset ps))
                      (if (or (.-done c) (.-busy c))
                        (do (ready c)
                            ((.-iterator c))
                            (throw (js/Error. "Undefined continuous flow.")))
                        (set! (.-busy c) true))
                      (let [p (.-choice ps)]
                        (set! (.-choice ps) c)
                        (if (nil? p)
                          (do (set! (.-prev c) c)
                              (set! (.-next c) c)
                              (top c))
                          (let [n (.-next p)]
                            (set! (.-prev c) p)
                            (set! (.-next c) n)
                            (set! (.-next p) c)
                            (set! (.-prev n) c))))
                      (recur)) x)))
              (catch :default e
                (when-some [ch (.-choice ps)]
                  (let [ch (.-next ch)]
                    (detach ch)
                    (set! (.-prev ch) nil)
                    (set! (.-next ch) nil)
                    (set! (.-choice ps) nil)
                    ((.-iterator ch))))
                (set! (.-notifier ps) nil) e))]
      (set! fiber f)
      (set! (.-coroutine ps) nil)
      (when (zero? (.-pending ps))
        ((.-terminator ps)))
      (if (nil? (.-notifier ps))
        (throw x) x))))

(defn suspend [^Process ps flow]
  (let [ch (->Choice ps nil nil nil nil true false)
        n #(let [cr (.-coroutine ps)]
             (ready ch)
             (if (nil? (.-coroutine ps))
               (when (zero? (.-pending ps))
                 ((.-terminator ps)))
               (when (nil? cr)
                 ((.-notifier ps)))))
        t #(do (set! (.-done ch) true) (n))]
    (set! (.-iterator ch) (flow n t))
    (set! (.-pending ps) (inc (.-pending ps))) ch))

(defn run [cr n t]
  (n) (->Process n t cr nil true 0))