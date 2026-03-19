(ns missionary.lincheck
  (:require [missionary.flow-protocol-enforcer :as enforcer])
  (:import (clojure.asm ClassWriter Opcodes Type)
           (clojure.asm.commons GeneratorAdapter Method)
           (clojure.lang Compiler IDeref IFn)
           (java.util.concurrent.atomic AtomicInteger)
           (missionary DummyFlow ProtocolViolation)
           (org.jetbrains.lincheck.datastructures StressOptions)))

(set! *warn-on-reflection* true)

;; ── Root consumer ─────────────────────────────────────────────────
;; Root implements IDeref (deref = transfer) and IFn (invoke = cancel).
;; We use clojure.lang interfaces instead of a custom definterface because
;; the ASM-generated test classes are loaded by the system class loader,
;; which can't resolve classes created by Clojure's DynamicClassLoader.

(def ^:const TRANSFERRED 0)
(def ^:const STEPPED 1)
(def ^:const CLAIMED 2)
(def ^:const STEPPED_DURING_TRANSFER 3)
(def ^:const DONE 4)

(defn ->root
  "Subscribe to flow, return IDeref (transfer) + IFn (cancel).
   The root manages the consumer state machine."
  [flow]
  (let [state      (AtomicInteger. TRANSFERRED)
        terminated (volatile! false)
        iterator   (volatile! nil)
        step-fn    (fn []
                     (.getAndUpdate state
                       (reify java.util.function.IntUnaryOperator
                         (applyAsInt [_ s]
                           (case s
                             0 STEPPED          ;; TRANSFERRED -> STEPPED
                             2 STEPPED_DURING_TRANSFER ;; CLAIMED -> SDT
                             4 DONE
                             (throw (AssertionError.
                                      (str "Protocol violation: step in state " s)))))))
                     nil)
        done-fn    (fn []
                     (vreset! terminated true)
                     ;; Atomically transition: only TRANSFERRED→DONE.
                     ;; If STEPPED, keep it — consumer must still transfer the last value.
                     ;; If CLAIMED/SDT, keep it — transfer is in progress.
                     (.getAndUpdate state
                       (reify java.util.function.IntUnaryOperator
                         (applyAsInt [_ s]
                           (case s
                             0 DONE       ;; TRANSFERRED -> DONE
                             4 DONE       ;; already DONE
                             s))))        ;; keep other states
                     nil)
        iter       (flow step-fn done-fn)]
    (vreset! iterator iter)
    (reify
      IDeref
      (deref [_]
        (if @terminated
          "skip"
          (let [old (.get state)]
            (if (not (= old STEPPED))
              "skip"
              (if (not (.compareAndSet state old CLAIMED))
                "skip"
                (let [ret (try @@iterator
                               (catch ProtocolViolation e (throw e))
                               (catch Exception e
                                 (str "err:" (.getSimpleName (class e)))))]
                  (.getAndUpdate state
                    (reify java.util.function.IntUnaryOperator
                      (applyAsInt [_ post]
                        (case post
                          2 TRANSFERRED  ;; CLAIMED -> TRANSFERRED
                          3 STEPPED      ;; SDT -> STEPPED
                          4 DONE
                          (throw (AssertionError.
                                   (str "Protocol violation: post-transfer state " post)))))))
                  ret))))))
      IFn
      (invoke [_]
        (when-not @terminated
          (@iterator))
        nil))))

;; ── DummyFlow ────────────────────────────────────────────────────

(definterface IDummyFlow
  (^String step [])
  (^String done [])
  (^String setThrow []))

(def ^:const DF-STEPPED   1)
(def ^:const DF-DONE      2)
(def ^:const DF-CANCELLED 4)
(def ^:const DF-CRASHED   8)

(defn ->dummy-flow
  "Create a Java DummyFlow instance."
  []
  (DummyFlow.))

(defn ->clj-dummy-flow
  "Controllable dummy flow for Lincheck testing (pure Clojure).
   Returns IDummyFlow + IFn. invoke(step, done) → iterator (IFn + IDeref)."
  []
  (let [state    (AtomicInteger. 0)
        should-throw (volatile! false)
        xfer-count   (volatile! 0)
        step-cb  (volatile! nil)
        done-cb  (volatile! nil)]
    (reify
      IDummyFlow
      (step [_]
        (loop []
          (let [old (.get state)]
            (if (pos? (bit-and old (bit-or DF-STEPPED DF-DONE DF-CRASHED)))
              ""
              (if (.compareAndSet state old (bit-or old DF-STEPPED))
                (do (@step-cb) "")
                (recur))))))
      (done [_]
        (loop []
          (let [old (.get state)]
            (cond
              (pos? (bit-and old DF-DONE)) ""
              (pos? (bit-and old DF-STEPPED)) ""
              (.compareAndSet state old (bit-or old DF-DONE))
              (do (@done-cb) "")
              :else (recur)))))
      (setThrow [_]
        (vreset! should-throw true)
        "armed")
      IFn
      (invoke [_ step done]
        (vreset! step-cb step)
        (vreset! done-cb done)
        (loop []
          (let [old (.get state)]
            (when-not (.compareAndSet state old (bit-or old DF-STEPPED))
              (recur))))
        (.invoke ^IFn @step-cb)
        (reify
          IFn
          (invoke [_]
            (loop []
              (let [old (.get state)]
                (when-not (.compareAndSet state old (bit-or old DF-CANCELLED))
                  (recur))))
            nil)
          IDeref
          (deref [_]
            (if @should-throw
              (do (loop []
                    (let [old (.get state)]
                      (when-not (.compareAndSet state old
                                  (bit-or (bit-and old (bit-not DF-STEPPED)) DF-CRASHED))
                        (recur))))
                  (throw (ex-info "intended crash" {})))
              (let [tc (vswap! xfer-count inc)
                    value (if (zero? (rem (inc tc) 3)) 0 (rem tc 3))]
                (loop []
                  (let [old (.get state)]
                    (when-not (.compareAndSet state old (bit-and old (bit-not DF-STEPPED)))
                      (recur))))
                value))))))))

;; ── Class generator ──────────────────────────────────────────────

(def ^:private ^String operation-descriptor
  "Lorg/jetbrains/lincheck/datastructures/Operation;")

(def ^:private ^Type object-type  (Type/getType Object))
(def ^:private ^Type string-type  (Type/getType String))
(def ^:private ^Type void-type    Type/VOID_TYPE)
(def ^:private ^Type ifn-type     (Type/getType IFn))
(def ^:private ^Type ideref-type  (Type/getType IDeref))
(def ^:private ^Type dummy-type   (Type/getType DummyFlow))

(def ^:private ^Method void-init  (Method. "<init>" void-type (into-array Type [])))

(defn- add-operation-annotation
  "Add @Operation annotation to a method visitor. group may be nil."
  [^GeneratorAdapter ga ^String group]
  (let [av (.visitAnnotation ga operation-descriptor true)]
    (when group
      (.visit av "nonParallelGroup" group))
    (.visitEnd av)))

(defn- with-violation-guard
  "Wrap body-fn output with try/catch for ProtocolViolation.
   body-fn should emit bytecode leaving return value on stack (or nothing for void).
   Handles returnValue, catch block (sets violationSeen + rethrows), and endMethod."
  [^GeneratorAdapter ga ^String class-internal body-fn]
  (let [pv-internal "missionary/ProtocolViolation"
        try-start   (.newLabel ga)
        try-end     (.newLabel ga)
        catch-block (.newLabel ga)
        ex-local    (.newLocal ga (Type/getObjectType pv-internal))]
    (.visitTryCatchBlock ga try-start try-end catch-block pv-internal)
    (.mark ga try-start)
    (body-fn)
    (.mark ga try-end)
    (.returnValue ga)
    (.mark ga catch-block)
    (.storeLocal ga ex-local)
    ;; first-writer-wins: only store if violationSeen is still null
    (let [skip-store (.newLabel ga)]
      (.loadThis ga)
      (.getField ga (Type/getObjectType class-internal) "violationSeen" object-type)
      (.ifNonNull ga skip-store)
      (.loadThis ga)
      (.loadLocal ga ex-local)
      (.putField ga (Type/getObjectType class-internal) "violationSeen" object-type)
      (.mark ga skip-store))
    (.loadLocal ga ex-local)
    (.visitInsn ga Opcodes/ATHROW)
    (.endMethod ga)))

(defn- emit-dummy-method
  "Emit a step/done/crash method that delegates to DummyFlow."
  [^ClassWriter cw ^String class-internal ^String method-name
   ^String group ^String dummy-method dummy-index]
  (let [ga (GeneratorAdapter. Opcodes/ACC_PUBLIC
             (Method. method-name void-type (into-array Type []))
             nil nil cw)]
    (add-operation-annotation ga group)
    (.visitCode ga)
    (with-violation-guard ga class-internal
      (fn []
        (.loadThis ga)
        (.getField ga (Type/getObjectType class-internal) "dummies" (Type/getType (Class/forName "[Ljava.lang.Object;")))
        (.push ga (int dummy-index))
        (.arrayLoad ga object-type)
        (.checkCast ga dummy-type)
        (.invokeVirtual ga dummy-type
          (Method. dummy-method string-type (into-array Type [])))
        (.pop ga)))))

(defn- emit-transfer-method
  "Emit the transfer @Operation method delegating to IDeref.deref()."
  [^ClassWriter cw ^String class-internal]
  (let [ga (GeneratorAdapter. Opcodes/ACC_PUBLIC
             (Method. "transfer" void-type (into-array Type []))
             nil nil cw)]
    (add-operation-annotation ga "consumer")
    (.visitCode ga)
    (with-violation-guard ga class-internal
      (fn []
        (.loadThis ga)
        (.getField ga (Type/getObjectType class-internal) "root" object-type)
        (.checkCast ga ideref-type)
        (.invokeInterface ga ideref-type
          (Method. "deref" object-type (into-array Type [])))
        (.pop ga)))))

(defn- emit-cancel-method
  "Emit the cancel @Operation method delegating to IFn.invoke()."
  [^ClassWriter cw ^String class-internal]
  (let [ga (GeneratorAdapter. Opcodes/ACC_PUBLIC
             (Method. "cancel" void-type (into-array Type []))
             nil nil cw)]
    (add-operation-annotation ga nil)
    (.visitCode ga)
    (with-violation-guard ga class-internal
      (fn []
        (.loadThis ga)
        (.getField ga (Type/getObjectType class-internal) "root" object-type)
        (.checkCast ga ifn-type)
        (.invokeInterface ga ifn-type
          (Method. "invoke" object-type (into-array Type [])))
        (.pop ga)))))

(defn- emit-validate-method
  "Emit @Validate method that throws AssertionError if violationSeen is non-null."
  [^ClassWriter cw ^String class-internal]
  (let [validate-descriptor "Lorg/jetbrains/lincheck/datastructures/Validate;"
        throwable-type (Type/getType Throwable)
        ga (GeneratorAdapter. Opcodes/ACC_PUBLIC
             (Method. "validate" void-type (into-array Type []))
             nil nil cw)]
    (let [av (.visitAnnotation ga validate-descriptor true)]
      (.visitEnd av))
    (.visitCode ga)
    (.loadThis ga)
    (.getField ga (Type/getObjectType class-internal) "violationSeen" object-type)
    (let [end (.newLabel ga)
          ae-type (Type/getType AssertionError)]
      (.ifNull ga end)
      ;; new AssertionError("Flow protocol violation detected", storedViolation)
      (.newInstance ga ae-type)
      (.dup ga)
      (.push ga "Flow protocol violation detected")
      (.loadThis ga)
      (.getField ga (Type/getObjectType class-internal) "violationSeen" object-type)
      (.checkCast ga throwable-type)
      (.invokeConstructor ga ae-type
        (Method. "<init>" void-type (into-array Type [string-type throwable-type])))
      (.visitInsn ga Opcodes/ATHROW)
      (.mark ga end))
    (.returnValue ga)
    (.endMethod ga)))

(defn- emit-constructor
  "Emit zero-arg constructor that calls setupFn.invoke()."
  [^ClassWriter cw ^String class-internal ^long arity]
  (let [ga (GeneratorAdapter. Opcodes/ACC_PUBLIC void-init nil nil cw)
        array-type (Type/getType (Class/forName "[Ljava.lang.Object;"))
        indexed-type (Type/getType clojure.lang.Indexed)]
    (.visitCode ga)
    ;; super()
    (.loadThis ga)
    (.invokeConstructor ga (Type/getType Object) void-init)
    ;; result = setupFn.invoke()
    (.getStatic ga (Type/getObjectType class-internal) "setupFn" ifn-type)
    (.invokeInterface ga ifn-type
      (Method. "invoke" object-type (into-array Type [])))
    ;; cast to Indexed for nth access
    (.checkCast ga indexed-type)
    (.dup ga)
    ;; this.root = result.nth(0)
    (.push ga (int 0))
    (.invokeInterface ga indexed-type
      (Method. "nth" object-type (into-array Type [Type/INT_TYPE])))
    (.loadThis ga)
    (.swap ga)
    (.putField ga (Type/getObjectType class-internal) "root" object-type)
    ;; this.dummies = new Object[arity]
    (.loadThis ga)
    (.push ga (int arity))
    (.newArray ga object-type)
    (.putField ga (Type/getObjectType class-internal) "dummies" array-type)
    ;; dummies[i] = result.nth(i+1) for each i
    (dotimes [i arity]
      (.dup ga) ;; dup Indexed
      (.push ga (int (inc i)))
      (.invokeInterface ga indexed-type
        (Method. "nth" object-type (into-array Type [Type/INT_TYPE])))
      (.loadThis ga)
      (.getField ga (Type/getObjectType class-internal) "dummies" array-type)
      (.swap ga)
      (.push ga (int i))
      (.swap ga)
      (.arrayStore ga object-type))
    (.pop ga) ;; pop remaining Indexed ref
    (.returnValue ga)
    (.endMethod ga)))

(def ^:private class-counter (atom 0))

(defn gen-lincheck-class
  "Generate a Lincheck test class with @Operation methods for the given arity.
   Returns the Class object. setup-fn is stored as a static field.
   Appends a unique counter to class-name so REPL reloads get fresh classes."
  [^String class-name ^long arity ^IFn setup-fn]
  (let [class-name (str class-name "_" (swap! class-counter inc))
        class-internal (.replace class-name \. \/)
        array-type     (Type/getType (Class/forName "[Ljava.lang.Object;"))
        cw             (Compiler/classWriter)]
    ;; Class header
    (.visit cw Opcodes/V11
      (bit-or Opcodes/ACC_PUBLIC Opcodes/ACC_SUPER)
      class-internal nil "java/lang/Object" nil)
    ;; Fields
    (.visitField cw (bit-or Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
      "setupFn" (.getDescriptor ifn-type) nil nil)
    (.visitField cw Opcodes/ACC_PUBLIC
      "root" (.getDescriptor object-type) nil nil)
    (.visitField cw Opcodes/ACC_PUBLIC
      "dummies" (.getDescriptor array-type) nil nil)
    (.visitField cw (bit-or Opcodes/ACC_PUBLIC Opcodes/ACC_VOLATILE)
      "violationSeen" (.getDescriptor object-type) nil nil)
    ;; Constructor
    (emit-constructor cw class-internal arity)
    ;; Dummy operations: step/done/crash per flow
    (dotimes [i arity]
      (let [group (str "f" i)]
        (emit-dummy-method cw class-internal (str "step" i) group "step" i)
        (emit-dummy-method cw class-internal (str "done" i) group "done" i)
        (emit-dummy-method cw class-internal (str "crash" i) group "setThrow" i)))
    ;; Root operations
    (emit-transfer-method cw class-internal)
    (emit-cancel-method cw class-internal)
    ;; @Validate method — fails the test if any ProtocolViolation was caught
    (emit-validate-method cw class-internal)
    (.visitEnd cw)
    ;; Write class file to lincheck-classes/ (on classpath) so Lincheck
    ;; worker threads can find it, then load it.
    (let [bytes (.toByteArray cw)
          path  (str "lincheck-classes/" (.replace class-internal "/" java.io.File/separator) ".class")
          file  (java.io.File. path)]
      (.mkdirs (.getParentFile file))
      (with-open [out (java.io.FileOutputStream. file)]
        (.write out ^bytes bytes))
      (let [cls (Class/forName class-name)
            f   (.getDeclaredField cls "setupFn")]
        (.setAccessible f true)
        (.set f nil setup-fn)
        cls))))

;; ── Macro + runner ───────────────────────────────────────────────

(defmacro def-lincheck-flow-test
  "Define a lincheck flow stress test.
   arity is the number of DummyFlow inputs.
   body must return a vector [root d0 d1 ...]
   where root is from ->root and d0..dn are DummyFlow instances."
  [name arity & body]
  (assert (integer? arity) "arity must be a literal integer")
  (let [class-name (str (munge (str *ns*)) "." (munge (str name)))]
    `(def ~name
       (gen-lincheck-class ~class-name ~arity (fn [] ~@body)))))

;; ── Agent bypass ────────────────────────────────────────────────

(def ^:private agent-bypass-done (atom false))

(defn- ensure-agent-bypass!
  "Load bootstrap.jar onto boot classpath, skip Lincheck agent install/uninstall.
   In STRESS mode, agent instrumentation is a no-op for non-Kotlin code."
  []
  (when-not @agent-bypass-done
    (let [^java.lang.instrument.Instrumentation inst
          (net.bytebuddy.agent.ByteBuddyAgent/install)
          ^java.security.ProtectionDomain pd
          (.getProtectionDomain org.jetbrains.lincheck.jvm.agent.LincheckJavaAgentKt)
          ^java.security.CodeSource cs (.getCodeSource pd)
          ^java.net.URL url (.getLocation cs)
          ^java.util.jar.JarFile jar (java.util.jar.JarFile. (java.io.File. (.toURI url)))
          tmp (doto (java.io.File/createTempFile "lincheck-bootstrap" ".jar") .deleteOnExit)]
      (with-open [^java.io.InputStream in (.getInputStream jar (.getEntry jar "bootstrap.jar"))
                  out (java.io.FileOutputStream. tmp)]
        (.transferTo in out))
      (.appendToBootstrapClassLoaderSearch inst (java.util.jar.JarFile. tmp))
      ;; Initialize LincheckJavaAgent lateinit fields so ensureObjectIsTransformed
      ;; doesn't crash. Field.get() bypasses the Kotlin getter (returns null, not throws).
      (let [agent-cls org.jetbrains.lincheck.jvm.agent.LincheckJavaAgent
            set-if-nil (fn [^String field-name value]
                         (let [f (.getDeclaredField agent-cls field-name)]
                           (.setAccessible f true)
                           (when-not (.get f nil)
                             (.set f nil value))))]
        (set-if-nil "instrumentation" inst)
        (set-if-nil "instrumentationStrategy"
          (first (java.util.EnumSet/allOf
                   org.jetbrains.lincheck.jvm.agent.InstrumentationStrategy)))
        (set-if-nil "instrumentationMode"
          (first (java.util.EnumSet/allOf
                   org.jetbrains.lincheck.jvm.agent.InstrumentationMode))))
      (org.jetbrains.lincheck.jvm.agent.LincheckJavaAgentKt/setTraceJavaAgentAttached true)
      (reset! agent-bypass-done true))))

(defn run-lincheck-stress-test
  "Run a Lincheck stress test on a generated test class.
   Protocol violations are detected by @Validate via the violationSeen flag."
  [^Class cls {:keys [iterations threads actors-per-thread invocations-per-iteration]
               :or   {iterations 100 threads 2
                      actors-per-thread 4 invocations-per-iteration 200}}]
  (ensure-agent-bypass!)
  (-> (StressOptions.)
    (.iterations iterations)
    (.threads threads)
    (.actorsPerThread actors-per-thread)
    (.invocationsPerIteration invocations-per-iteration)
    (.check cls)))
