(ns ^:no-doc missionary.impl.Reductions)

(declare transfer)
(deftype Process [^:mutable reducer ^:mutable notifier terminator ^:mutable result ^:mutable input ^:mutable busy ^:mutable done]
  #?(:cljs IFn :cljd cljd.core/IFn)
  (-invoke [_] (input))
  #?(:cljs IDeref :cljd cljd.core/IDeref)
  (-deref [p] (transfer p)))

(defn ready [^Process p]
  (loop [cb nil]
    (if (set! (.-busy p) (not (.-busy p)))
      (if (.-done p)
        (.-terminator p)
        (if (nil? (.-reducer p))
          (do (try @(.-input p) (catch #?(:cljs :default :cljd Exception) _))
              (recur cb)) (.-notifier p))) cb)))

(defn transfer [^Process ps]
  (try
    (let [f (.-reducer ps)
          r (.-result ps)
          r (if (identical? r ps)
              (f) (f r @(.-input ps)))]
      (set! (.-result ps)
        (if (reduced? r)
          (do ((.-input ps))
              (set! (.-reducer ps) nil)
              @r) r)))
    (catch #?(:cljs :default :cljd Exception) e
      ((.-input ps))
      (set! (.-notifier ps) nil)
      (set! (.-reducer ps) nil)
      (set! (.-result ps) e)))
  (when-some [cb (ready ps)] (cb))
  (if (nil? (.-notifier ps))
    (throw (.-result ps))
    (.-result ps)))

(defn run [rf f n t]
  (let [ps (->Process rf n t nil nil true false)]
    (set! (.-result ps) ps)
    (set! (.-input ps)
      (f #(when-some [cb (ready ps)] (cb))
        #(do (set! (.-done ps) true)
             (when-some [cb (ready ps)]
               (cb))))) (n) ps))
