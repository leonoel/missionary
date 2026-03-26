(ns missionary.flow-protocol-enforcer2
  "Flow protocol enforcer that does not itself violate the protocol.
   All violations are reported via a caller-provided callback.
   The enforcer is a pure pass-through — observable behavior is identical
   to not having the enforcer present.

   See also: missionary.flow-protocol-enforcer (original, throws from
   step/done on violation — which is itself a protocol violation)."
  #?(:clj (:import [clojure.lang IDeref IFn]
                   [missionary ProtocolViolation])))

(defn- make-violation
  ([nm msg]
   #?(:clj  (ProtocolViolation. (str (pr-str nm) " flow protocol violation: " msg))
      :cljs (ex-info (str (pr-str nm) " flow protocol violation: " msg) {})))
  ([nm msg e]
   #?(:clj  (ProtocolViolation. (str (pr-str nm) " flow protocol violation: " msg) e)
      :cljs (ex-info (str (pr-str nm) " flow protocol violation: " msg) {} e))))

(defn- try-run
  "Call f. Returns nil on success, the exception on failure."
  [f]
  (try (f) nil (catch #?(:clj Throwable :cljs :default) e e)))

(defn ->process-state []
  (let [s (object-array [nil false nil])]
    [(fn steped?  ([] (aget s (int 0))) ([x] (aset s (int 0) x)))
     (fn done?    ([] (aget s (int 1))) ([x] (aset s (int 1) x)))
     (fn crashed? ([] (aget s (int 2))) ([x] (aset s (int 2) x)))]))

(defn flow
  "Wrap input-flow with protocol violation detection.
   on-violation: (fn [^ProtocolViolation e] ...) — called on each violation. Must not throw.
   nm: name for this flow (used in violation messages).
   input-flow: the flow to wrap.
   Returns a flow."
  ([on-violation nm input-flow] (flow on-violation nm input-flow {}))
  ([on-violation nm input-flow {:keys [ready-on-init] :or {ready-on-init true}}]
   (fn [step done]
     (let [[stepped? done? crashed?] (->process-state)
           step (fn []
                  (cond
                    (done?)    (on-violation (make-violation nm "step after done"))
                    (crashed?) (on-violation (make-violation nm "step after crash"))
                    (stepped?) (on-violation (make-violation nm "double step"))
                    :else      (stepped? true))
                  (when-some [e (try-run step)]
                    (on-violation (make-violation nm "step cannot throw" e))
                    (throw e)))
           done (fn []
                  (cond
                    (stepped?) (on-violation (make-violation nm "done after step without transfer"))
                    (done?)    (on-violation (make-violation nm "done called twice"))
                    :else      (done? true))
                  (when-some [e (try-run done)]
                    (on-violation (make-violation nm "done cannot throw" e))
                    (throw e)))
           iter (try (input-flow step done)
                     (catch #?(:clj Throwable :cljs :default) e
                       (on-violation (make-violation nm "flow process creation threw" e))
                       (throw e)))]
       (when (and ready-on-init (not (stepped?)))
         (on-violation (make-violation nm "missing initial step")))
       (reify
         IFn (#?(:clj invoke :cljs -invoke) [_]
               (when-some [e (try-run iter)]
                 (on-violation (make-violation nm "cancel cannot throw" e))
                 (throw e)))
         IDeref (#?(:clj deref :cljs -deref) [_]
                  (let [s (stepped?)]
                    (cond
                      (crashed?) (on-violation (make-violation nm "transfer after crash"))
                      (not s)    (on-violation (make-violation nm (if (nil? s) "transfer without initial step" "double transfer")))
                      :else      (stepped? false)))
                  (try @iter
                       (catch #?(:clj Throwable :cljs :default) e
                         (crashed? e)
                         (throw e)))))))))
