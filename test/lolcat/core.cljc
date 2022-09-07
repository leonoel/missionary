(ns ^{:doc "
Base functionality for lolcat, a minimalist concatenative language based on clojure. Lolcat programs are sequences of
instructions and compose with `clojure.core/concat`. Primitive instructions are provided by constructors `push`, `copy`,
`drop` and `call`.
"} lolcat.core
  (:refer-clojure :exclude [drop]))

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
              (assert (pos? index) "Stack exhausted.")
              (conj stack (nth stack (dec index)))))
          (stack-drop [stack offset]
            (let [index (- (count stack) offset)]
              (assert (pos? index) "Stack exhausted.")
              (into (subvec stack 0 (dec index))
                (subvec stack index))))]
    (fn [inst]
      (case (:op inst)
        :push (context update :stack conj (:value inst))
        :copy (context update :stack stack-copy (:offset inst))
        :drop (context update :stack stack-drop (:offset inst))
        :call (let [{:keys [events stack]} (context identity)
                    index (doto (- (count stack) (:arity inst))
                            (-> pos? (assert "Stack exhausted.")))]
                (context assoc
                  :events (seq (:events inst))
                  :stack (subvec stack 0 (dec index)))
                (context update :stack conj
                  (try (let [x (apply (nth stack (dec index))
                                 (subvec stack index))]
                         (rethrow!) x)
                       (catch #?(:clj Throwable :cljs :default) e
                         (rethrow!) (throw e))))
                (when-some [events (:events (context identity))]
                  (throw (ex-info "Missing events." {:events events})))
                (context assoc :events events))))))

(def ^{:doc "
Return a pair containing the number of values respectively consumed and produced by the composition of given programs.
"} balance
  (letfn [(apply-stack-effect [r consumed produced]
            (let [d (- consumed (second r))]
              [(+ (first r) (max 0 d))
               (- produced (min 0 d))]))
          (events-stack-effect [r events]
            (reduce event-stack-effect r events))
          (event-stack-effect [r insts]
            (-> r
              (apply-stack-effect 0 1)
              (insts-stack-effect insts)
              (apply-stack-effect 1 0)))
          (insts-stack-effect [r insts]
            (reduce inst-stack-effect r insts))
          (inst-stack-effect [r inst]
            (case (:op inst)
              :push (apply-stack-effect r 0 1)
              :copy (let [consumed (inc (:offset inst))]
                      (apply-stack-effect r consumed (inc consumed)))
              :drop (let [produced (:offset inst)]
                      (apply-stack-effect r (inc produced) produced))
              :call (-> r
                      (apply-stack-effect (inc (:arity inst)) 0)
                      (events-stack-effect (:events inst))
                      (apply-stack-effect 0 1))))]
    (fn [& ps] (reduce insts-stack-effect [0 0] ps))))

(def ^{:doc "
Must be called as a side effect of the evaluation of a `call` instruction providing the event definition as a program.
Produce given value, evaluate the program, consume a value and return it to the caller.
"} event
  (fn [x]
    (assert (context identity) "Undefined context.")
    (try (rethrow!)
         (let [[evt & evts] (doto (:events (context identity))
                              (when-not (throw (ex-info "Spurious event." {:value x}))))]
           (context update :stack conj x)
           (context assoc :events evts)
           (run! eval-inst! evt)
           (let [stack (:stack (context identity))]
             (assert (pos? (count stack)) "Stack exhausted.")
             (context assoc :stack (pop stack))
             (peek stack)))
         (catch #?(:clj Throwable :cljs :default) e
           (context assoc :error e)
           (throw (#?(:clj Error. :cljs js/Error.) "crashed"))))))

(def ^{:doc "
Transform the right end of given vector by evaluating the composition of given programs.
"} run
  (fn [v & ps]
    (assert (vector? v))
    (let [p (context identity)]
      (context {} {:stack v})
      (try (run! eval-inst! (apply concat ps))
           (:stack (context identity))
           (finally (context {} p))))))

(def ^{:doc "
Return a program consuming nothing and producing given values.
(= (run [] (push x y z)) [x y z])
"} push
  (fn
    ([] [])
    ([x] [{:op :push :value x}])
    ([x & xs] (into (push x) (mapcat push) xs))))

(def ^{:doc "
Return a program consuming (inc n) values and producing this list appended with a copy of the head.
(= (run [x y z] (copy 2)) [x y z x])
"} copy
  (fn [n]
    (assert (nat-int? n))
    [{:op :copy :offset n}]))

(def ^{:doc "
Return a program consuming n values and producing the tail of this list.
(= (run [x y z] (drop 2)) [y z])
"} drop
  (fn [n]
    (assert (nat-int? n))
    [{:op :drop :offset n}]))

(def ^{:doc "
Return a program consuming (inc n) values and calling the head as a clojure function, passing the tail as arguments,
and producing the returned value. The function must call `event` as a side effect successively for each program
provided as extra arguments.
(= (run [inc 1] (call 1)) [2])
(= (run [event x] (call 1 (concat (drop 0) (push y)))) [y])
"} call
  (fn [n & ps]
    (assert (nat-int? n))
    [{:op :call :arity n :events ps}]))