(ns  missionary.flow-protocol-enforcer
  #?(:clj (:import [clojure.lang IDeref IFn]))
  #?(:cljs (:require-macros [missionary.flow-protocol-enforcer :refer [cannot-throw]])))

(defn violated
  ([nm msg]
   (throw (ex-info (str (pr-str nm) " flow protocol violation: " msg) {}))
   ;; (println (pr-str nm) "flow protocol violation:" msg)
   ;; #?(:cljs (.error js/console) :clj (prn (Throwable.)))
   )
  ([nm msg e]
   (throw (ex-info (str (pr-str nm) " flow protocol violation: " msg) {} e))
   ;; (println (pr-str nm) "flow protocol violation:" msg)
   ;; (#?(:clj prn :cljs js/console.error) e)
   ))

(defmacro cannot-throw [nm f] `(try (~f) (catch ~(if (:js-globals &env) :default 'Throwable) e#
                                        (violated ~nm ~(str f " cannot throw") e#))))

(defn flow
  ([input-flow] (flow "" input-flow))
  ([nm input-flow]
   (when-not (or (string? nm) (symbol? nm) (keyword? nm))
     #?(:clj (prn :what-flow-is-this (Throwable.)) :cljs (.trace js/console "what flow is this")))
   (fn [step done]
     (let [!should-step? (atom ::init), !done? (atom false), !crashed? (atom false), !v (atom ::init)
           step (fn []
                  (when @!done? (violated nm "step after done"))
                  (when @!crashed? (violated nm "step after crash"))
                  (if (first (swap-vals! !should-step? not)) (cannot-throw nm step) (violated nm "double step")))
           done (fn []
                  (when (false? @!should-step?) (violated nm "done after step without transfer"))
                  (if (first (reset-vals! !done? true)) (violated nm "done called twice") (cannot-throw nm done)))
           cancel (try (input-flow step done)
                       (catch #?(:clj Throwable :cljs :default) e (violated nm "flow process creation threw" e)))]
       (when (= ::init @!should-step?) (violated nm "missing initial step"))
       (reify
         IFn (#?(:clj invoke :cljs -invoke) [_] (cannot-throw nm cancel))
         IDeref (#?(:clj deref :cljs -deref) [_]
                  (when @!crashed? (violated nm "transfer after crash"))
                  (if-let [should-step (first (swap-vals! !should-step? not))]
                    (let [[t v] (try [:ok (reset! !v @cancel)] (catch #?(:clj Throwable :cljs :default) e [:ex e]))]
                      (violated nm (if (= ::init should-step) "transfer without initial step" "double transfer!!!"))
                      ;; (prn 'double-transfer 'from @!v 'to (reset! !v v))
                      ;; (when (= :ex t) (#?(:clj prn :cljs js/console.error) v))
                      (if (= :ex t) (throw v) v))
                    (try (reset! !v @cancel)
                         (catch #?(:clj Throwable :cljs :default) e
                           (reset! !crashed? true)
                           (throw e))))))))))
