(ns lolcat.core
  (:refer-clojure :exclude [drop]))

;; INSTS
;; {:op :push :value x} put value x on top of the stack.
;; {:op :copy :offset n} put a duplicate of the nth value on top of the stack.
;; {:op :drop :offset n} discard the nth value from the stack.
;; {:op :call :arity arity :events [evt1 evt2 ...]}
;; Remove the top (inc arity) values from the stack and call the head as a function, passing the tail as arguments.
;; The function must call the probe as a side effect successively for each event provided. For each probe call, the
;; argument passed to the probe is put on top of the stack, then the event is evaluated as a sequence of instructions,
;; then the value on top of the stack is removed and returned to the probe caller.

(def context
  #?(:clj
     (let [tl (ThreadLocal.)]
       (fn [f & args]
         (let [x (apply f (.get tl) args)]
           (.set tl x) x)))
     :cljs
     (let [ar (object-array 1)]
       (fn [f & args]
         (aset ar 0 (apply f (aget ar 0) args))))))

(defn stack-copy [stack offset]
  (let [index (- (count stack) offset)]
    (assert (pos? index) "Stack exhausted.")
    (conj stack (nth stack (dec index)))))

(defn stack-drop [stack offset]
  (let [index (- (count stack) offset)]
    (assert (pos? index) "Stack exhausted.")
    (into (subvec stack 0 (dec index))
      (subvec stack index))))

(defn rethrow! []
  (when (contains? (context identity) :error)
    (let [e (:error (context identity))]
      (context dissoc :error) (throw e))))

(defn eval-inst! [inst]
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
            (context assoc :events events))))

(defn run [stack & programs]
  (let [p (context identity)]
    (context {} {:stack (vec stack)})
    (try (run! eval-inst! (apply concat programs))
         (:stack (context identity))
         (finally (context {} p)))))

(defn event [x]
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
         (throw (#?(:clj Error. :cljs js/Error.) "crashed")))))

(defn compose-stack-effects
  ([] [0 0])
  ([x] x)
  ([x y]
   (let [d (- (first y) (second x))]
     [(+ (first x) (max 0 d))
      (- (second y) (min 0 d))]))
  ([x y & zs] (reduce compose-stack-effects (compose-stack-effects x y) zs)))

(declare inst-stack-effect)

(defn program-stack-effect [& programs]
  (transduce (map inst-stack-effect) compose-stack-effects (apply concat programs)))

(defn event-stack-effect [event]
  (compose-stack-effects [0 1] (program-stack-effect event) [1 0]))

(defn inst-stack-effect [inst]
  (case (:op inst)
    :push [0 1]
    :copy ((juxt identity inc) (inc (:offset inst)))
    :drop ((juxt inc identity) (:offset inst))
    :call (compose-stack-effects
            [(inc (:arity inst)) 0]
            (transduce (map event-stack-effect)
              compose-stack-effects (:events inst))
            [0 1])))

(defn push
  ([] [])
  ([x] [{:op :push :value x}])
  ([x & xs] (into (push x) (mapcat push) xs)))

(defn copy [offset]
  [{:op :copy :offset offset}])

(defn drop [offset]
  [{:op :drop :offset offset}])

(defn call [arity & events]
  [{:op :call :arity arity :events events}])