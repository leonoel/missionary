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

(defn- try-call
  "Call f. Returns nil on success, the exception on failure."
  [f]
  (try (f) nil (catch #?(:clj Throwable :cljs :default) e e)))

(defn flow
  "Wrap input-flow with protocol violation detection.
   on-violation: (fn [^ProtocolViolation e] ...) — called on each violation. Must not throw.
   nm: name for this flow (used in violation messages).
   input-flow: the flow to wrap.
   Returns a flow."
  ([on-violation nm input-flow] (flow on-violation nm input-flow {}))
  ([on-violation nm input-flow opts]
   (let [{:keys [ready-on-init] :or {ready-on-init true}} opts]
   (fn [step done]
     (let [!should-step? (atom ::init)
           !done?        (atom false)
           !crashed?     (atom nil)
           step (fn []
                  (cond
                    @!done?    (on-violation (make-violation nm "step after done"))
                    @!crashed? (on-violation (make-violation nm "step after crash"))
                    (not @!should-step?)
                    (on-violation (make-violation nm "double step"))
                    :else      (reset! !should-step? false))
                  (when-some [e (try-call step)]
                    (on-violation (make-violation nm "step cannot throw" e))
                    (throw e)))
           done (fn []
                  (cond
                    (false? @!should-step?)
                    (on-violation (make-violation nm "done after step without transfer"))
                    @!done? (on-violation (make-violation nm "done called twice"))
                    :else   (reset! !done? true))
                  (when-some [e (try-call done)]
                    (on-violation (make-violation nm "done cannot throw" e))
                    (throw e)))
           iter (try (input-flow step done)
                     (catch #?(:clj Throwable :cljs :default) e
                       (on-violation (make-violation nm "flow process creation threw" e))
                       (throw e)))]
       (when (and ready-on-init (= ::init @!should-step?))
         (on-violation (make-violation nm "missing initial step")))
       (reify
         IFn (#?(:clj invoke :cljs -invoke) [_]
               (when-some [e (try-call iter)]
                 (on-violation (make-violation nm "cancel cannot throw" e))
                 (throw e)))
         IDeref (#?(:clj deref :cljs -deref) [_]
                  (let [s @!should-step?]
                    (cond
                      @!crashed? (on-violation (make-violation nm "transfer after crash"))
                      s          (on-violation (make-violation nm
                                                 (if (= ::init s)
                                                   "transfer without initial step"
                                                   "double transfer")))
                      :else      (reset! !should-step? true)))
                  (try @iter
                       (catch #?(:clj Throwable :cljs :default) e
                         (reset! !crashed? e)
                         (throw e))))))))))
