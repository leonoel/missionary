(ns ^:no-doc missionary.impl.Sequential
  (:require [missionary.impl.Fiber :refer [Fiber fiber]])
  (:import missionary.Cancelled))

(defn nop [])

(declare kill suspend)

(deftype Process [coroutine success failure resume rethrow ^boolean busy ^boolean failed current token]
  IFn
  (-invoke [this] (kill this) nil)
  Fiber
  (park [this task] (suspend this task))
  (swich [_ _] (throw (js/Error. "Unsupported operation.")))
  (fork [_ _ _] (throw (js/Error. "Unsupported operation.")))
  (check [_] (when (nil? token) (throw (Cancelled. "Process cancelled."))))
  (unpark [this]
    (let [x current]
      (set! (.-current this) nil)
      (when failed
        (set! (.-failed this) false)
        (throw x)) x)))

(defn kill [^Process ps]
  (when-some [c (.-token ps)]
    (set! (.-token ps) nil) (c)))

(defn suspend [^Process ps task]
  (let [c (task (.-resume ps)
            (.-rethrow ps))]
    (if (nil? (.-token ps))
      (c) (set! (.-token ps) c))) ps)

(defn step [^Process ps]
  (when (set! (.-busy ps) (not (.-busy ps)))
    (let [prev fiber]
      (set! fiber ps)
      (try
        (loop []
          (let [x ((.-coroutine ps))]
            (if (identical? x ps)
              (when (set! (.-busy ps) (not (.-busy ps)))
                (recur)) ((.-success ps) x))))
        (catch :default e
          ((.-failure ps) e)))
      (set! fiber prev))))

(defn run [cr s f]
  (let [ps (->Process cr s f nil nil false false nil nop)]
    (set! (.-resume ps)
      (fn [x]
        (set! (.-current ps) x)
        (step ps) nil))
    (set! (.-rethrow ps)
      (fn [e]
        (set! (.-failed ps) true)
        (set! (.-current ps) e)
        (step ps) nil))
    (step ps) ps))