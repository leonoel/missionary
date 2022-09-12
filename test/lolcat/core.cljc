(ns ^{:doc "
Base functionality for lolcat, a minimalist concatenative language based on clojure. A lolcat program is a sequence of
instructions, which can be either :
* an instance of copy
* an instance of drop
* an instance of call
* an instance of word
* any other value
"} lolcat.core
  (:refer-clojure :exclude [drop]))

(defrecord Copy [offset])
(defrecord Drop [offset])
(defrecord Call [arity events])
(defrecord Word [f args])

(def ^:no-doc context
  #?(:clj
     (let [tl (ThreadLocal.)]
       (fn [f & args]
         (let [x (apply f (.get tl) args)]
           (.set tl x) x)))
     :cljs
     (let [ar (object-array 1)]
       (fn [f & args]
         (aset ar 0 (apply f (aget ar 0) args))))))

(def ^:no-doc rethrow!
  #(when (contains? (context identity) :error)
     (let [e (:error (context identity))]
       (context dissoc :error) (throw e))))

(def ^:no-doc eval-inst!
  (letfn [(stack-copy [stack offset]
            (let [index (- (count stack) offset)]
              (conj stack (nth stack (dec index)))))
          (stack-drop [stack offset]
            (let [index (- (count stack) offset)]
              (into (subvec stack 0 (dec index))
                (subvec stack index))))
          (call-attempt [words stack arity]
            (let [f (dec (count stack))
                  r (try (constantly (apply (peek stack) (subvec stack (- f arity) f)))
                         (catch #?(:clj Throwable :cljs :default) e
                           #(throw (ex-info "Call failure."
                                     {:arity arity
                                      :stack stack
                                      :words words} e))))
                  {:keys [words stack events]} (context identity)]
              (rethrow!)
              (when (some? events)
                (throw (ex-info "Missing events." {:words words :stack stack :events events})))
              (conj stack (r))))]
    (fn [inst]
      (cond
        (instance? Copy inst)
        (context update :stack stack-copy (:offset inst))

        (instance? Drop inst)
        (context update :stack stack-drop (:offset inst))

        (instance? Call inst)
        (let [{:keys [events stack words]} (context identity)
              arity (:arity inst)]
          (context assoc
            :events (seq (:events inst))
            :stack (subvec stack 0 (- (count stack) (inc arity))))
          (context assoc
            :events events
            :stack (call-attempt words stack arity)))

        (instance? Word inst)
        (let [{:keys [f args]} inst]
          (context update :words conj f)
          (run! eval-inst! (apply f args))
          (context update :words pop))

        :push (context update :stack conj inst)))))

(def ^{:doc "
Return a pair containing the number of values respectively consumed and produced by the composition of given programs.
"} balance
  (letfn [(apply-stack-effect [r consumed produced]
            (let [d (- consumed (second r))]
              [(+ (first r) (max 0 d))
               (- produced (min 0 d))]))
          (events-stack-effect [r events]
            (reduce event-stack-effect r events))
          (event-stack-effect [r inst]
            (-> r
              (apply-stack-effect 0 1)
              (inst-stack-effect inst)
              (apply-stack-effect 1 0)))
          (insts-stack-effect [r insts]
            (reduce inst-stack-effect r insts))
          (inst-stack-effect [r inst]
            (cond
              (instance? Copy inst)
              (let [consumed (inc (:offset inst))]
                (apply-stack-effect r consumed (inc consumed)))

              (instance? Drop inst)
              (let [produced (:offset inst)]
                (apply-stack-effect r (inc produced) produced))

              (instance? Call inst)
              (-> r
                (apply-stack-effect (inc (:arity inst)) 0)
                (events-stack-effect (:events inst))
                (apply-stack-effect 0 1))

              (instance? Word inst)
              (insts-stack-effect r (apply (:f inst) (:args inst)))

              :push (apply-stack-effect r 0 1)))]
    (fn [& insts] (insts-stack-effect [0 0] insts))))

(def ^{:doc "
Must be called as a side effect of the evaluation of a `call` instruction providing the event definition as a program.
Produce given value, evaluate the program, consume a value and return it to the caller.
"} event
  (fn [x]
    (assert (context identity) "Undefined context.")
    (try (rethrow!)
         (let [{:keys [words stack events]} (context identity)]
           (when (nil? events)
             (throw (ex-info "Spurious event." {:words words :stack stack :event x})))
           (context assoc :stack (conj stack x) :events (next events))
           (eval-inst! (first events))
           (let [stack (:stack (context identity))]
             (context assoc :stack (pop stack))
             (peek stack)))
         (catch #?(:clj Throwable :cljs :default) e
           (context assoc :error e)
           (throw (#?(:clj Error. :cljs js/Error.) "crashed"))))))

(def ^{:doc "
Evaluate given instructions.
"} run
  (fn [& insts]
    (let [[required] (apply balance insts)]
      (when (pos? required)
        (throw (ex-info (str "Insufficient data - required " required) {:insts insts})))
      (let [p (context identity)]
        (context {} {:words [] :stack []})
        (try (run! eval-inst! insts)
             (:stack (context identity))
             (finally (context {} p)))))))

(def ^{:doc "
Return a program consuming (inc n) values and producing this list appended with a copy of the head.
(= (run [x y z] (copy 2)) [x y z x])
"} copy
  (fn [n]
    (assert (nat-int? n))
    (->Copy n)))

(def ^{:doc "
Return a program consuming n values and producing the tail of this list.
(= (run [x y z] (drop 2)) [y z])
"} drop
  (fn [n]
    (assert (nat-int? n))
    (->Drop n)))

(def ^{:doc "
Return a program consuming (inc n) values and calling the last as a clojure function, passing the rest as arguments,
and producing the returned value. The function must call `event` as a side effect successively for each program
provided as extra arguments.
(= (run [inc 1] (call 1)) [2])
(= (run [event x] (call 1 (list (drop 0) (push y)))) [y])
"} call
  (fn [n & ps]
    (assert (nat-int? n))
    (->Call n ps)))

(defn word [f & args]
  (->Word f args))

(defmacro defword [sym args & body]
  `(def ~sym (partial word (fn ~sym ~args ~@body))))