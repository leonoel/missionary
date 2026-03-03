(ns missionary.impl.varhandle
  (:refer-clojure :exclude [get set])
  (:require [hasch.core :as h])
  (:import (clojure.asm MethodVisitor Opcodes Type)
           (clojure.asm.commons GeneratorAdapter Method)
           (clojure.java.api Clojure)
           (clojure.lang DynamicClassLoader IFn RT)
           (java.lang.invoke MethodHandles VarHandle VarHandle$AccessMode)
           (java.nio ByteOrder)))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn object-field-handle [target n t]
  (-> (MethodHandles/privateLookupIn target (MethodHandles/lookup))
    (.findVarHandle target (name n) t)))

(defn static-field-handle [target n t]
  (-> (MethodHandles/privateLookupIn target (MethodHandles/lookup))
    (.findStaticVarHandle target (name n) t)))

(defn array-element-handle [^Class c]
  (MethodHandles/arrayElementVarHandle c))

;; (byte-array-view-handle (Class/forName "[I") ByteOrder/BIG_ENDIAN)
(defn byte-array-view-handle [^Class c ^ByteOrder bo]
  (MethodHandles/byteArrayViewVarHandle c bo))

(defn emit-access-mode [method]
  (let [m (VarHandle$AccessMode/valueFromMethodName (name method))]
    (symbol (str (.getName ^Class (type m)) "/" (.name m)))))

;; slow
(defmacro $ [method handle & args]
  `(.invokeWithArguments
     (MethodHandles/varHandleExactInvoker ~(emit-access-mode method)
       (.accessModeType ^VarHandle ~handle ~(emit-access-mode method)))
     (doto (object-array ~(inc (count args)))
       (aset 0 ~handle)
       ~@(eduction
           (map-indexed
             (fn [^long i arg]
               `(aset ~(inc i) ~arg)))
           args))))

;; probably slower
(defmacro $$ [method handle & args]
  `(.invokeWithArguments
     (.toMethodHandle ^VarHandle ~handle ~(emit-access-mode method))
     (doto (object-array ~(count args))
       ~@(eduction
           (map-indexed
             (fn [i arg]
               `(aset ~i ~arg)))
           args))))

(defn define-class [emit ^String id & args]
  (when *compile-files*
    (Compiler/writeClassFile id (apply emit id args)))
  (intern *ns* (symbol id)
    (or (RT/loadClassForName id)
      (.defineClass ^DynamicClassLoader @Compiler/LOADER
        id (apply emit id args) nil))))

(defn field-class ^Class [^Class c ^String f]
  (.getType (doto (.getDeclaredField c f)
              (.setAccessible true))))

(defn class-type ^Type [^Class c]
  (Type/getType c))

(def primitive-class
  (into {} (map (fn [^Class c] [(symbol (.getName c)) c]))
    #{Void/TYPE
      Boolean/TYPE
      Byte/TYPE
      Character/TYPE
      Short/TYPE
      Integer/TYPE
      Long/TYPE
      Float/TYPE
      Double/TYPE}))

(def primitive-boxed
  {Void/TYPE Void
   Boolean/TYPE Boolean
   Byte/TYPE Byte
   Character/TYPE Character
   Short/TYPE Short
   Integer/TYPE Integer
   Long/TYPE Long
   Float/TYPE Float
   Double/TYPE Double})

(defn hint-class ^Class [hint]
  (or (primitive-class hint)
    (resolve hint)
    (throw (Exception. (str "Unable to resolve class - " hint)))))

(defn class-name ^String [^Class c]
  (.getName c))

(defn dot->slash ^String [^String s]
  (.replace s \. \/))

(def emit-arguments
  (partial reduce-kv
    (fn [^MethodVisitor mv i ^Class arg]
      (doto mv (.visitVarInsn (.getOpcode (class-type arg) Opcodes/ILOAD) i)))))

(def method-entrypoint "invoke")

(defn emit-method-bytecode [^String id ^String method-name ^Class return-class ^Class target-class arg-classes]
  (let [static-arg-classes (into [target-class] arg-classes)
        cw (Compiler/classWriter)]
    (.visit cw Opcodes/V9 (bit-or Opcodes/ACC_PUBLIC Opcodes/ACC_ABSTRACT Opcodes/ACC_INTERFACE)
      id nil (.getInternalName (class-type Object)) nil)
    (doto (GeneratorAdapter. (bit-or Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
            (Method. method-entrypoint (class-type return-class)
              (into-array Type (eduction (map class-type) static-arg-classes)))
            nil nil cw)
      (.visitCode)
      (emit-arguments static-arg-classes)
      (.visitMethodInsn Opcodes/INVOKEVIRTUAL (.getInternalName (class-type target-class)) method-name
        (Type/getMethodDescriptor (class-type return-class)
          (into-array Type (eduction (map class-type) arg-classes))) false)
      (.returnValue)
      (.endMethod))
    (.toByteArray cw)))

(defn value-of [^GeneratorAdapter ga ^Class primitive]
  (let [boxed (class-type (primitive-boxed primitive))]
    (.invokeStatic ga boxed
      (Method. "valueOf" boxed
        (into-array Type [(class-type primitive)])))))

(defn name-class ^Class [^String n]
  (try (Class/forName n)
       (catch ClassNotFoundException _)))

(def emit-invoke
  (letfn [(emit-form [^GeneratorAdapter ga form]
            (cond
              (nil? form)
              (doto ga
                (.visitInsn Opcodes/ACONST_NULL))

              (string? form)
              (doto ga
                (.push (str form)))

              (boolean? form)
              (doto ga
                (.push (boolean form))
                (value-of Boolean/TYPE))

              (number? form)
              (cond
                (instance? Integer form)
                (doto ga
                  (.push (int form))
                  (value-of Integer/TYPE))

                (instance? Long form)
                (doto ga
                  (.push (long form))
                  (value-of Long/TYPE))

                (instance? Float form)
                (doto ga
                  (.push (float form))
                  (value-of Float/TYPE))

                (instance? Double form)
                (doto ga
                  (.push (double form))
                  (value-of Double/TYPE)))

              (keyword? form)
              (doto ga
                (emit-invoke
                  (if-some [ns (namespace form)]
                    [`keyword ns (name form)]
                    [`keyword (name form)])))

              (symbol? form)
              (if-some [ns (namespace form)]
                (if-some [c (name-class ns)]
                  (doto ga
                    (.getStatic (class-type c) (name form)
                      (class-type (field-class c (name form)))))
                  (doto ga
                    (.push ns)
                    (.push (name form))
                    (.invokeStatic (class-type Clojure)
                      (Method. "var" (class-type IFn)
                        (into-array Type (repeat 2 (class-type Object)))))))
                (doto ga
                  (.push (Type/getObjectType (dot->slash (name form))))))

              (seq? form)
              (doto ga
                (emit-invoke (cons `list form)))

              (vector? form)
              (doto ga
                (emit-invoke (cons `vector form)))

              (set? form)
              (doto ga
                (emit-invoke (cons `hash-set form)))

              (map? form)
              (doto ga
                (emit-invoke (cons `hash-map (interleave (keys form) (vals form)))))))
          (emit-invoke [^GeneratorAdapter ga forms]
            (reduce emit-form ga forms)
            (.invokeInterface ga (class-type IFn)
              (Method. "invoke" (class-type Object)
                (into-array Type (repeat (dec (count forms)) (class-type Object))))))]
    emit-invoke))

(def static-entrypoint "result")

(defn emit-static-bytecode [^String id forms]
  (let [cw (Compiler/classWriter)]
    (.visit cw Opcodes/V9 (bit-or Opcodes/ACC_PUBLIC Opcodes/ACC_ABSTRACT Opcodes/ACC_INTERFACE)
      id nil (.getInternalName (class-type Object)) nil)
    (.visitField cw (bit-or Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC Opcodes/ACC_FINAL)
      static-entrypoint (.getDescriptor (class-type Object)) nil nil)
    (doto (GeneratorAdapter. Opcodes/ACC_STATIC (Method. "<clinit>" Type/VOID_TYPE (into-array Type [])) nil nil cw)
      (.visitCode)
      (emit-invoke forms)
      (.putStatic (Type/getObjectType id) static-entrypoint (class-type Object))
      (.returnValue)
      (.endMethod))
    (.toByteArray cw)))

(defmacro method [return-hint method-name target-hint target-value & arg-hints-values]
  (let [target-class (hint-class target-hint)
        [arg-hints arg-values] (when arg-hints-values
                                 (apply map vector (partition-all 2 arg-hints-values)))
        arg-classes (into [] (map hint-class) arg-hints)
        id (-> [method-name]
             (into (map class-name) (cons target-class arg-classes))
             (h/edn-hash) (h/uuid5) (str))]
    (define-class emit-method-bytecode id (name method-name) (hint-class return-hint) target-class arg-classes)
    (cons (symbol id method-entrypoint) (cons target-value arg-values))))

(defmacro static [& forms]
  (let [resolved-forms
        (into []
          (map (fn resolve-symbols [form]
                 (cond
                   (or (nil? form) (boolean? form) (number? form) (string? form) (keyword? form))
                   form

                   (symbol? form)
                   (if-some [r (resolve form)]
                     (symbol (if (class? r) (class-name r) r))
                     (if-some [ns (namespace form)]
                       (if-some [r (resolve (symbol ns))]
                         (symbol (if (class? r) (class-name r) r) (name form))
                         form) form))

                   (seq? form)
                   (sequence (map resolve-symbols) form)

                   (vector? form)
                   (into [] (map resolve-symbols) form)

                   (set? form)
                   (into #{} (map resolve-symbols) form)

                   (map? form)
                   (into {} (map (juxt (comp resolve-symbols key) (comp resolve-symbols val))) form))))
          forms)
        id (-> resolved-forms (h/edn-hash) (h/uuid5) (str))]
    (define-class emit-static-bytecode id resolved-forms)
    (symbol id static-entrypoint)))

(defn resolve-field-hint [target-name field-name]
  (-> (resolve target-name)
    (or (throw (ClassNotFoundException. target-name)))
    (field-class (name field-name))
    (class-name)
    (symbol)))

(defn expand-primitive [hint]
  (if-some [prim (primitive-class hint)]
    (symbol (class-name (primitive-boxed prim)) "TYPE")
    hint))

(defn emit-object-field-jvm [target-name field-name]
  `(static object-field-handle ~target-name ~(keyword field-name)
     ~(expand-primitive (resolve-field-hint target-name field-name))))

(defn emit-object-field-js [target-name field-name]
  (let [obj (with-meta (gensym "obj") {:tag target-name})
        field (list (symbol (str ".-" (name field-name))) ~obj)
        value (gensym "value")]
    `(fn
       ([~obj] ~field)
       ([~obj ~value] (set! ~field ~value)))))

(defmacro object-field [desc]
  ((if (:js-globals &env)
     emit-object-field-js
     emit-object-field-jvm)
   (symbol (namespace desc))
   (symbol (name desc))))

(defn mode-signature [mode type]
  (case mode
    (:get :getVolatile :getAcquire :getOpaque)
    [type []]

    (:set :setVolatile :setAcquire :setOpaque)
    ['void [type]]

    (:compareAndExchange :compareAndExchangeAcquire :compareAndExchangeRelease)
    [type [type type]]

    (:compareAndSet :weakCompareAndSetPlain :weakCompareAndSet :weakCompareAndSetAcquire :weakCompareAndSetRelease)
    ['boolean [type type]]

    (:getAndSet :getAndSetAcquire :getAndSetRelease :getAndAdd :getAndAddAcquire :getAndAddRelease :getAndBitwiseOr :getAndBitwiseOrAcquire :getAndBitwiseOrRelease :getAndBitwiseAnd :getAndBitwiseAndAcquire :getAndBitwiseAndRelease :getAndBitwiseXor :getAndBitwiseXorAcquire :getAndBitwiseXorRelease)
    [type [type]]))

(defn emit-access-jvm [method-name hint handle args]
  (let [field-hint (symbol
                     (class-name
                       (if (nil? hint)
                         Object (hint-class hint))))
        [return-hint arg-hints] (mode-signature method-name field-hint)]
    `(method ~return-hint ~method-name VarHandle ~handle
       ~@(-> (case (- (count args) (count arg-hints))
               0 '[]
               1 '[Object]
               2 '[Object int])
           (into arg-hints)
           (interleave args)))))

(defn emit-access-js [method-name hint handle args]
  (let [[return-hint arg-hints] (mode-signature method-name (or hint 'object))]
    (with-meta (cons handle args) {:tag return-hint})))

(defmacro access [mode handle & args]
  ((if (:js-globals &env) emit-access-js emit-access-jvm)
   (keyword (name mode))
   (when-some [ns (namespace mode)]
     (symbol ns))
   handle args))

(defn emit-field-access [env mode desc inst & args]
  (let [target-name (symbol (namespace desc))
        field-name (symbol (name desc))]
    (if (:js-globals env)
      (let [field-access (cons (symbol (str ".-" field-name)) (first args))]
        (case mode
          :get field-access
          :set (list `set! field-access (second args))))
      (let [hint (resolve-field-hint target-name field-name)
            field-hint (symbol
                         (class-name
                           (if (nil? hint)
                             Object (hint-class hint))))
            [return-hint arg-hints] (mode-signature mode field-hint)]
        `(method ~return-hint ~mode VarHandle
           (static object-field-handle ~target-name
             ~(keyword field-name) ~(expand-primitive hint))
           Object ~inst ~@(interleave arg-hints args))))))

(defmacro get [desc inst]
  (emit-field-access &env :get desc inst))

(defmacro set [desc inst x]
  (emit-field-access &env :set desc inst x))

(defmacro get-volatile [desc inst]
  (emit-field-access &env :getVolatile desc inst))

(defmacro set-volatile [desc inst x]
  (emit-field-access &env :setVolatile desc inst x))

(defmacro compare-and-set [desc inst x y]
  (emit-field-access &env :compareAndSet desc inst x y))

(defmacro get-and-bitwise-xor [desc inst x]
  (emit-field-access &env :getAndBitwiseXor desc inst x))

(defmacro get-and-add [desc inst x]
  (emit-field-access &env :getAndAdd desc inst x))

(defmacro get-and-set [desc inst x]
  (emit-field-access &env :getAndSet desc inst x))