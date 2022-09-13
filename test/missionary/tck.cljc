(ns missionary.tck
  (:require [#?(:clj clojure.test :cljs cljs.test)])
  #?(:cljs (:require-macros [missionary.tck :refer [if-try deftest is]]))
  #?(:clj (:import (java.util.concurrent LinkedBlockingQueue)
                   (java.util.concurrent.atomic AtomicReference)
                   (java.util ArrayDeque Queue))))

(defmacro if-try [[binding attempt] success failure]
  `(let [[cond# ~binding]
         (try [true ~attempt]
              (catch ~(if (:js-globals &env)
                        :default `Throwable) e#
                [false e#]))]
     (if cond# ~success ~failure)))

(defmacro deftest [& args]
  (cons (if (:js-globals &env) 'cljs.test/deftest 'clojure.test/deftest) args))

(defmacro is [& args]
  (cons (if (:js-globals &env) 'cljs.test/is 'clojure.test/is) args))

(defn check! [b x]
  #?(:clj
     (let [aqueue (LinkedBlockingQueue.)
           squeue (ArrayDeque.)
           timers (AtomicReference. #{})
           thread (Thread/currentThread)]
       (letfn [(actor
                 ([x]
                  (.add ^Queue (if (identical? thread (Thread/currentThread))
                                 squeue aqueue) (case x nil thread x)))
                 ([x d]
                  (let [t (Thread. (reify Runnable
                                     (run [_]
                                       (try (Thread/sleep d) (actor x)
                                            (catch InterruptedException _)))))]
                    (loop []
                      (when-some [ts (.get timers)]
                        (if (.compareAndSet timers ts (conj ts t))
                          (.start t) (recur)))))))]
         (actor x)
         (try
           (loop [b b]
             (let [x (if-some [x (.poll squeue)] x (.take aqueue))]
               (when-some [b (b actor (when-not (identical? thread x) x))]
                 (recur b))))
           (catch Throwable e
             (is (throw e))))
         (doseq [^Thread t (.getAndSet timers nil)]
           (.interrupt t))
         ;; make test runners happy
         (is (= nil nil))))

     :cljs
     (cljs.test/async done
       (let [state (volatile! b)
             timers (volatile! #{})]
         ((fn a
            ([x]
             (when-some [s @state]
               (or (vreset! state
                            (if (vector? s)
                              (conj s x)
                              (try (vreset! state [])
                                   (loop [b (s a x)]
                                     (let [s @state]
                                       (when-not (= [] s)
                                         (vreset! state [])
                                         (recur (reduce (fn [b x] (or (b a x) (reduced nil))) b s)))))
                                   (catch :default e (is (throw e)) nil))))
                   (let [ts @timers]
                     (doseq [t ts] (js/clearTimeout t))
                     (vreset! timers nil) (done)))))
            ([x d]
             (when-some [ts @timers]
               (vreset! timers (conj ts (js/setTimeout #(a x) d))))))
          x)))))

(defn task-spec [opts]
  (let [[status predicate]
        (or (and (not (contains? opts :failure)) (find opts :success))
            (and (not (contains? opts :success)) (find opts :failure))
            (throw (ex-info "Either :success or :failure must be defined." {})))]
    (letfn [(booting [self task]
              (when-some [c (:cancel opts)] (self :cancel c))
              (self :timeout (:timeout opts 0))
              (waiting (task (fn [x] (self [:success x]))
                             (fn [e] (self [:failure e])))))
            (waiting [cancel]
              (fn continue [self event]
                (case event
                  :cancel (do (cancel) continue)
                  :timeout (throw (ex-info "Task timed out." {}))
                  (let [[s r] event]
                    (if (= status s)
                      (when-not (predicate r)
                        (throw (ex-info "Task result did not satisfy given predicate." {})))
                      (throw (ex-info "Task terminated with unexpected status." {})))
                    (cancel) (self :done) exiting))))
            (exiting [_ event]
              (case event
                :done nil
                (:cancel :timeout) exiting
                (throw (ex-info "Task called continuation more than once." {}))))]
      booting)))

(defmacro deftask [name opts task]
  `(deftest ~name (check! (task-spec ~opts) ~task)))

(defn flow-spec [opts]
  (letfn [(booting [self flow]
            (when-some [c (:cancel opts)] (self :cancel c))
            (self :timeout (:timeout opts 0))
            (flowing (flow #(self :ready) #(self :terminated))
                     (seq (:results opts)) (:failure opts)))
          (flowing [iterator results failure]
            (fn continue [self event]
              (case event
                :cancel (do (iterator) continue)
                :timeout (throw (ex-info "Flow timed out." {}))
                :ready (if-try [x @iterator]
                         (if-some [[p & results] results]
                           (if (p x)
                             (flowing iterator results failure)
                             (throw (ex-info "Flow value did not satisfy given predicate." {:value x})))
                           (throw (ex-info "Flow produced an unexpected value." {:value x})))
                         (if (nil? results)
                           (if-some [p failure]
                             (if (p x)
                               (flowing iterator nil nil)
                               (throw (ex-info "Flow error did not satisfy given predicate." {:error x})))
                             (throw (ex-info "Flow failed althought it was expected to terminate." {:error x})))
                           (throw (ex-info "Flow failed althought it was expected to produce a value." {:error x}))))
                :terminated (if (nil? results)
                              (if (nil? failure)
                                (do (iterator) (self :done) exiting)
                                (throw (ex-info "Flow terminated althought it was expected to fail." {})))
                              (throw (ex-info "Flow terminated althought it was expected to produce more values." {}))))))
          (exiting [_ event]
            (case event
              :done nil
              (:cancel :timeout) exiting
              :ready (throw (ex-info "Flow called notifier after terminator." {}))
              :terminated (throw (ex-info "Flow called terminator more than once." {}))))]
    booting))

(defmacro defflow [name opts flow]
  `(deftest ~name (check! (flow-spec ~opts) ~flow)))
