(ns ^:no-doc missionary.impl.Continuous
  (:require [missionary.impl.Fiber :refer [Fiber fiber]])
  (:import missionary.Cancelled))

(declare ack kill transfer suspend)

(deftype Process [notifier terminator choice]
  IFn
  (-invoke [this] (kill this))
  IDeref
  (-deref [this] (transfer this))
  Fiber
  (park [_ _] (throw (js/Error. "Unsupported operation.")))
  (fork [_ _ _] (throw (js/Error. "Unsupported operation.")))
  (swich [this flow] (suspend this flow))
  (check [_] (when-not (.-live choice) (throw (Cancelled. "Process cancelled."))))
  (unpark [_] @(.-iterator (.-prev choice))))

(deftype Choice [process prev next coroutine iterator ^boolean live ^boolean busy ^boolean done])

(defn nop [])

(defn boot [cr ^Process ps]
  (let [prev (.-choice ps)
        next (.-next prev)]
    (->> (->Choice ps prev next cr nop (.-live prev) true false)
      (set! (.-choice ps))
      (set! (.-next prev))
      (set! (.-prev next)))
    nil))

(defn cancel [^Choice c]
  (let [ps (.-process c)]
    (loop [c c]
      (when (.-live c)
        (set! (.-live c) false)
        ((.-iterator c))
        (when-some [t (.-choice ps)]
          (let [c (loop [c c]
                    (let [c (.-next c)]
                      (if (nil? (.-prev c))
                        (recur c) c)))]
            (when-not (identical? c (.-next t))
              (recur c))))))))

(defn kill [^Process ps]
  (when-some [c (.-choice ps)]
    (cancel (.-next c))))

(defn done [^Process ps]
  (let [c (.-choice ps)
        p (.-prev c)]
    (set! (.-prev c) nil)
    (if (identical? p c)
      (do (set! (.-choice ps) nil)
          ((.-terminator ps)))
      (let [n (.-next c)]
        (set! (.-prev n) p)
        (set! (.-next p) n)
        (set! (.-choice ps) p)
        (ack p)))))

(defn dirty [^Process ps]
  (let [n (.-notifier ps)]
    ((.-coroutine (.-choice ps)) boot ps)
    (if (nil? n)
      (let [prev fiber]
        (set! fiber ps)
        (try (while (identical? ((.-coroutine (.-choice ps))) ps))
             (catch :default _))
        (set! fiber prev)
        (done ps)) (n))))

(defn ack [^Choice c]
  (when (.-busy c)
    (when (.-done c)
      (set! (.-busy c) false)
      (set! (.-done c) false)
      (dirty (.-process c)))))

(defn ready [^Choice c]
  (when (set! (.-busy c) (not (.-busy c)))
    (let [ps (.-process c)
          cr (.-coroutine c)]
      (if (.-done c)
        (let [p (.-prev c)]
          (set! (.-prev c) nil)
          (if (identical? c p)
            (do (set! (.-choice ps) nil)
                ((.-terminator ps)))
            (let [n (.-next c)]
              (set! (.-prev n) p)
              (set! (.-next p) n)
              (when (identical? c (.-choice ps))
                (set! (.-choice ps) p)
                (ack p)))))
        (if (nil? cr)
          (do (set! (.-busy c) false)
              (try @(.-iterator c) (catch :default _)))
          (if (identical? c (.-choice ps))
            (do (set! (.-busy c) false)
                (dirty ps))
            (do (set! (.-done c) true)
                (cancel (.-next c)))))))))

(defn transfer [^Process ps]
  (let [prev fiber]
    (set! fiber ps)
    (try (loop []
           (let [x ((.-coroutine (.-choice ps)))]
             (if (identical? x ps) (recur) x)))
         (catch :default e
           (set! (.-notifier ps) nil)
           (cancel (.-next (.-choice ps)))
           (throw e))
         (finally
           (set! fiber prev)
           (done ps)))))

(defn suspend [^Process ps flow]
  (let [c (.-choice ps)]
    (set! (.-iterator c)
      (flow (fn [] (ready c) nil)
        (fn [] (set! (.-done c) true)
          (ready c) nil)))
    ((.-coroutine c) boot ps)
    (when (.-done c)
      (ready c)
      (throw (js/Error. "Undefined continuous flow.")))
    (when (.-busy c)
      (set! (.-busy c) false)
      (set! (.-coroutine c) nil)
      ((.-iterator c))
      (throw (js/Error. "Undefined continuous flow.")))
    (when-not (.-live c) ((.-iterator c))) ps))

(defn run [cr n t]
  (let [ps (->Process n t nil)
        c (->Choice ps nil nil cr nop true true false)]
    (->> c
      (set! (.-next c))
      (set! (.-prev c))
      (set! (.-choice ps)))
    (n) ps))