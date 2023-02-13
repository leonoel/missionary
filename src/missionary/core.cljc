(ns missionary.core
  (:refer-clojure :exclude [reduce reductions eduction group-by])
  (:require [cloroutine.core :refer [cr] :include-macros true])
  (:import (missionary.impl Reduce Reductions GroupBy Relieve Latest Sample Reactor Fiber Sequential Ambiguous
                            Continuous Watch Observe Buffer Rendezvous Dataflow Mailbox Semaphore RaceJoin Sleep
                            Never Seed Eduction Zip #?(:clj Thunk) #?(:clj Pub) #?(:clj Sub))
           #?(:clj org.reactivestreams.Publisher))
  #?(:cljs (:require-macros [missionary.core :refer [sp ap cp amb amb> amb= ! ? ?> ?< ?? ?! ?= holding reactor]])))


(def
  ^{:static true
    :doc "A `java.util.concurrent.Executor` optimized for blocking evaluation."}
  blk #?(:clj Thunk/blk))


(def
  ^{:static true
    :doc "A `java.util.concurrent.Executor` optimized for non-blocking evaluation."}
  cpu #?(:clj Thunk/cpu))


(defn
  ^{:static true
    :arglists '([executor thunk])
    :doc "
Same as `via`, except the expression to evaluate is provided as a zero-arity function on second argument.

Not supported on clojurescript.

```clojure
(? (via-call blk read-line))
;; reads a line from stdin and returns it
```
"} via-call [e t]
  (fn [s f]
    #?(:clj (Thunk/run e t s f)
       :cljs (throw (js/Error. "Unsupported operation.")))))


(defmacro
  ^{:arglists '([executor & body])
    :doc "
Returns a task evaluating body (in an implicit `do`) on given `java.util.concurrent.Executor` and completing with its result.

Cancellation interrupts the evaluating thread.

Not supported on clojurescript.

Example :
```clojure

```
"} via [exec & body] `(via-call ~exec #(do ~@body)))


(defn
  ^{:static true
    :arglists '([duration] [duration value])
    :doc "
Returns a task completing with given value (nil if not provided) after given duration (in milliseconds).

Cancelling a sleep task makes it fail immediately.

Example :
```clojure
(? (sleep 1000 42))
#_=> 42               ;; 1 second later
```
"} sleep
  ([d] (sleep d nil))
  ([d x] (fn [s f] (Sleep/run d x s f))))


(defn
  ^{:static true
    :arglists '([f & tasks])
    :doc "
Returns a task running given `tasks` concurrently.

If every task succeeds, `join` completes with the result of applying `f` to these results.

If any task fails, others are cancelled then `join` fails with this error.

Cancelling propagates to children tasks.

Example :
```clojure
(? (join vector (sleep 1000 1) (sleep 1000 2)))
#_=> [1 2]            ;; 1 second later
```
"} join
  ([c] (fn [s _] (s (c)) #(do)))
  ([c & ts] (fn [s f] (RaceJoin/run false c ts s f))))


(defn ^{:static true :no-doc true} race-failure [& errors]
  (ex-info "Race failure." {::errors errors}))


(defn
  ^{:static true
    :arglists '([& tasks])
    :doc "
Returns a task running given `tasks` concurrently.

If any task succeeds, others are cancelled then `race` completes with this result.

If every task fails, `race` fails.

Cancelling propagates to children tasks.

Example :
```clojure
(? (race (sleep 1000 1) (sleep 2000 2)))
#_=> 1                 ;; 1 second later
```
"} race
  ([] (fn [_ f] (f (race-failure)) #(do)))
  ([& ts] (fn [s f] (RaceJoin/run true race-failure ts s f))))


(defn
  ^{:static true
    :arglists '([task])
    :doc "
Returns a task always succeeding with result of given `task` wrapped in a zero-argument function returning result if successful or throwing exception if failed.
"} attempt [task]
  (fn [s _] (task (fn [x] (s #(-> x))) (fn [e] (s #(throw e))))))


(defn
  ^{:static true
    :arglists '([task])
    :doc "
Returns a task running given `task` completing with a zero-argument function and completing with the result of this function call.
"} absolve [task]
  (fn [s f]
    (task
      (fn [t]
        (try (s (t))
             (catch #?(:clj Throwable
                       :cljs :default) e
               (f e)))) f)))


(def
  ^{:static true
    :arglists '([task delay] [task delay value])
    :doc "
Returns a task running given `task` and completing with its result if available within specified `delay` (in
milliseconds). Otherwise, input is cancelled and the process succeeds with `value`, or `nil` if not provided.

```clojure
(m/? (m/timeout (m/sleep 20 :a) 25 :b)) ;; :a after 20ms
(m/? (m/timeout (m/sleep 20 :a) 15 :b)) ;; :b after 15ms
(m/? (m/timeout (m/sleep 20 :a) 15))    ;; nil after 15ms
```
"} timeout
  (fn timeout
    ([task delay] (timeout task delay nil))
    ([task delay value]
     (-> task
       (attempt)
       (race (sleep delay #(-> value)))
       (absolve)))))


(defn ^:no-doc check []
  (Fiber/check (Fiber/current)))

(defn ^:no-doc park [task]
  (Fiber/park (Fiber/current) task))

(defn ^:no-doc switch [flow]
  (Fiber/swich (Fiber/current) flow))

(defn ^:no-doc fork [par flow]
  (assert (pos? par) "Non-positive parallelism.")
  (Fiber/fork (Fiber/current) par flow))

(defn ^:no-doc unpark []
  (Fiber/unpark (Fiber/current)))

(defn ^:no-doc sp-run [c s f]
  (Sequential/run c s f))

(defn ^:no-doc ap-run [c n t]
  (Ambiguous/run c n t))

(defmacro ! "
Throws an instance of `missionary.Cancelled` if current evaluation context is interrupted, otherwise returns nil. The
evaluation context defaults to the current thread if the host platform supports it, and can be redefined with `sp`,
`ap`, or `cp`.
" [] `(check))


(defmacro ? "
Parks current evaluation context by given task. Evaluation resumes when the task completes, result is returned or
rethrown according to completion status. Interrupting the evaluation context cancels the parking task. The evaluation
context defaults to the current thread if the host platform supports it, and can be redefined with `sp` or `ap`.
" [task] `(park ~task))


(defmacro ?> "
Forks current evaluation context by given flow. Evaluation resumes whenever the flow transfers, result is returned or
rethrown according to transfer status. Each transfer creates a new processing branch and defines a new evaluation
context inherited from its parent. `par` is an optional positive number, defaulting to 1, defining the maximal count
of processing branches allowed to be run concurrently. Interrupting the parent evaluation context cancels the forking
flow and interrupts all processing branches currently being run and those subsequently run. The evaluation context is
undefined by default and can be defined with `ap`.

Example :
```clojure
(require '[missionary.core :as m])
(m/? (m/reduce conj (m/ap (inc (m/?> (m/seed [1 2 3]))))))
#_=> [2 3 4]
```

Example :
```clojure
(require '[missionary.core :as m])
(m/? (->> (m/ap
            (let [x (m/?> 5 (m/seed [19 57 28 6 87]))]
              (m/? (m/sleep x x))))
       (m/reduce conj)))
#_=> [6 19 28 57 87]    ;; in 87 ms
```
" ([flow] `(fork 1 ~flow))
  ([par flow] `(fork ~par ~flow)))


(defmacro ^{:deprecated true
            :doc "Alias for `?>`"}
  ?? [f] `(?> ~f))


(defmacro ?< "
Forks current evaluation context by given flow. Evaluation resumes whenever the flow transfers, result is returned or
rethrown according to transfer status. Each transfer creates a new processing branch and defines a new evaluation
context inherited from its parent. Concurrent processing branches are not allowed, and the current processing branch is
interrupted when the forking flow becomes ready to transfer again. Interrupting the parent evaluation context cancels
the forking flow and interrupts all processing branches currently being run and those subsequently run. The evaluation
context is undefined by default and can be defined with `ap` or `cp`.

Example :
```clojure
(require '[missionary.core :as m])
(import missionary.Cancelled)

(defn debounce [delay flow]
  (m/ap (let [x (m/?< flow)]
          (try (m/? (m/sleep delay x))
               (catch Cancelled _ (m/amb))))))

(m/? (->> (m/ap (let [n (m/amb 24 79 67 34 18 9 99 37)]
                  (m/? (m/sleep n n))))
       (debounce 50)
       (m/reduce conj)))
#_=> [24 79 9 37]
```
" [flow] `(switch ~flow))


(defmacro ^{:deprecated true
            :doc "Alias for `?<`"}
  ?! [f] `(?< ~f))


(defmacro
  ^{:arglists '([flow])
    :deprecated true
    :doc "Alias for `(?> ##Inf flow)`"}
  ?= [flow] `(?> ##Inf ~flow))


(defmacro
  ^{:arglists '([& body])
    :doc "
Returns a task evaluating `body` (in an implicit `do`) in a new evaluation context and completing its result. Body
evaluation can be parked by a task with `?`. Cancelling a `sp` process interrupts its evaluation context.
"} sp [& body]
  `(partial
     (cr {park unpark}
       ~@body) sp-run))

(defn ^:no-doc cp* [cr] (Continuous/flow cr))

(defmacro
  ^{:arglists '([& body])
    :doc "
Returns a continuous flow evaluating `body` (in an implicit `do`) in a new evaluation context and producing values of
each subsequent fork. Body evaluation can be forked by a continuous flow with `?<`. Evaluation and transfers are lazy,
driven by downstream sampling. Cancelling an `cp` process interrupts its root evaluation context.
"} cp [& body]
  `(cp*
     (cr {switch unpark}
       ~@body)))


(defmacro
  ^{:arglists '([& body])
    :doc "
Returns a discrete flow evaluating `body` (in an implicit `do`) in a new evaluation context and producing values of each
subsequent fork. Body evaluation can be parked by a task with `?` and forked by a flow with `?>` and `?<`. Evaluation
and transfers are eager, backpressured by downstream transfers. Cancelling an `ap` process interrupts its root
evaluation context.
"} ap [& body]
  `(partial
     (cr {park unpark
          fork unpark
          switch unpark}
       ~@body) ap-run))


(defn
  ^{:static true
    :arglists '([task])
    :doc "
Inhibits cancellation signal of given `task`.
"} compel [task]
  (fn [s f] (task s f) #(do)))


(defn
  ^{:static true
    :arglists '([])
    :doc "
Creates an instance of dataflow variable (aka single-assignment).

A dataflow variable is a function implementing `assign` on 1-arity and `deref` on 2-arity (as task). `assign` immediately binds the variable to given value if not already bound and returns bound value. `deref` is a task completing with the value bound to the variable as soon as it's available.

Cancelling a `deref` task makes it fail immediately.
```
"} dfv [] (Dataflow/make))


(defn
  ^{:static true
    :arglists '([])
    :doc "
Creates an instance of mailbox.

A mailbox is a function implementing `post` on 1-arity and `fetch` on 2-arity (as task). `post` immediately pushes given value to mailbox and returns nil. `fetch` is a task pulling a value from mailbox as soon as it's non-empty and completing with this value.

Cancelling a `fetch` task makes it fail immediately.

Example : an actor is a mailbox associated with a process consuming messages.
```clojure
(defn crash [^Throwable e]                                ;; let it crash philosophy
  (.printStackTrace e)
  (System/exit -1))

(defn actor
  ([init] (actor init crash))
  ([init fail]
   (let [self (mbx)]
     ((sp
        (loop [b init]
          (recur (b self (? self)))))
       nil fail)
     self)))

(def counter
  (actor
    ((fn beh [n]
       (fn [self cust]
         (cust n)
         (beh (inc n)))) 0)))

(counter prn)                                             ;; prints 0
(counter prn)                                             ;; prints 1
(counter prn)                                             ;; prints 2
```
"} mbx [] (Mailbox/make))


(defn
  ^{:static true
    :arglists '([])
    :doc "
Creates an instance of synchronous rendez-vous.

A synchronous rendez-vous is a function implementing `give` on its 1-arity and `take` on its 2-arity (as task). `give` takes a value to be transferred and returns a task completing with nil as soon as a taker is available. `take` is a task completing with transferred value as soon as a giver is available.

Cancelling `give` and `take` tasks makes them fail immediately.

Example : producer / consumer stream communication
```clojure
(defn reducer [rf i take]
  (sp
    (loop [r i]
      (let [x (? take)]
        (if (identical? x take)
          r (recur (rf r x)))))))

(defn iterator [give xs]
  (sp
    (loop [xs (seq xs)]
      (if-some [[x & xs] xs]
        (do (? (give x))
            (recur xs))
        (? (give give))))))

(def stream (rdv))

(? (join {} (iterator stream (range 100)) (reducer + 0 stream)))      ;; returns 4950
```
"} rdv [] (Rendezvous/make))


(defn
  ^{:static true
    :arglists '([] [n])
    :doc "
Creates a semaphore initialized with n tokens (1 if not provided, aka mutex).

A semaphore is a function implementing `release` on 0-arity and `acquire` on 2-arity (as task). `release` immediately makes a token available and returns nil. `acquire` is a task completing with nil as soon as a token is available.

Cancelling an `acquire` task makes it fail immediately.

Example : dining philosophers
```clojure
(defn phil [name f1 f2]
  (sp
    (while true
      (prn name :thinking)
      (? (sleep 500))
      (holding f1
        (holding f2
          (prn name :eating)
          (? (sleep 600)))))))

(def forks (vec (repeatedly 5 sem)))

(? (timeout 10000
     (join vector
       (phil \"descartes\" (forks 0) (forks 1))
       (phil \"hume\"      (forks 1) (forks 2))
       (phil \"plato\"     (forks 2) (forks 3))
       (phil \"nietzsche\" (forks 3) (forks 4))
       (phil \"kant\"      (forks 0) (forks 4)))))
```
"} sem
  ([] (sem 1))
  ([n] (Semaphore/make n)))


(defmacro
  ^{:arglists '([semaphore & body])
    :doc "
`acquire`s given `semaphore` and evaluates `body` (in an implicit `do`), ensuring `semaphore` is `release`d after evaluation.
"} holding [lock & body]
  `(let [l# ~lock] (? l#) (try ~@body (finally (l#)))))


(def never
  ^{:static true
    :doc "
A task never succeeding. Cancelling makes it fail immediately."}
  (fn [_ f] (Never/run f)))


(def
  ^{:static true
    :doc "
The empty flow. Doesn't produce any value and terminates immediately. Cancelling has no effect.

Example :
```clojure
(? (reduce conj none))
#_=> []
```
"} none (fn [_ t] (t) #(do)))


(defn
  ^{:static true
    :arglists '([collection])
    :doc "
Returns a discrete flow producing values from given `collection`. Cancelling before having reached the end makes the flow fail immediately.
"} seed [coll]
  (fn [n t] (Seed/run coll n t)))

(def ^{:deprecated true
       :doc "Alias for `seed`"}
  enumerate seed)


(defmacro
  ^{:arglists '([& forms])
    :doc "In an `ap` block, evaluates each form sequentially and returns successive results."}
  amb
  ([] `(?> none))
  ([form] form)
  ([form & forms]
   (let [n (inc (count forms))]
     `(case (?> (seed (range ~n)))
        ~@(interleave (range) (cons form forms))))))


(defmacro
  ^{:arglists '([& forms])
    :doc "In an `ap` block, evaluates each form concurrently and returns results in order of availability."}
  amb=
  ([] `(?> none))
  ([form] form)
  ([form & forms]
   (let [n (inc (count forms))]
     `(case (?> ~n (seed (range ~n)))
        ~@(interleave (range) (cons form forms))))))


(defmacro
  ^{:deprecated true
    :arglists '([& forms])
    :doc "Alias for `amb`"}
  amb> [& forms] (cons `amb forms))


(defn
  ^{:static true
    :arglists '([rf flow] [rf init flow])
    :doc "
Returns a task reducing values produced by given discrete `flow` with `rf`, starting with `init` (or, if not provided, the result of calling `rf` with no argument).

Cancelling propagates to upstream flow. Early termination by `rf` (via `reduced` or throwing) cancels upstream flow.

Example :
```clojure
(? (reduce + (seed (range 10))))
#_=> 45
```
"} reduce
  ([rf flow] (fn [s f] (Reduce/run rf flow s f)))
  ([rf i flow] (reduce (fn ([] i) ([r x] (rf r x))) flow)))

(def ^{:deprecated true
       :doc "Alias for `reduce`"}
  aggregate reduce)


(defn
  ^{:static true
    :arglists '([reference])
    :doc "
Returns a continuous flow reflecting the current state of a reference type. `reference` must support `add-watch`,
`remove-watch` and `deref`. On initialization, the process is ready to transfer. On transfer, the current state is
returned. Whenever the state of the reference changes and a transfer is not pending, the process becomes ready to
transfer again. Cancelling the process makes it fail immediately with an instance of `missionary.Cancelled` and
terminates the process.
"} watch [r] (fn [n t] (Watch/run r n t)))


(defn
  ^{:static true
    :arglists '([subject])
    :doc "
Returns a discrete flow observing values produced by a non-backpressured subject. `subject` must be a function taking a
callback and returning a cleanup thunk. On initialization, the process calls the subject with a fresh callback. Passing
a value to the callback makes the process ready to transfer this value. Cancelling the process makes it fail immediately
with an instance of `missionary.Cancelled` and terminates the process. The cleanup thunk is called on termination. The
callback throws an `Error` if the process is cancelled or terminated, or if a transfer is pending.
"} observe [s] (fn [n t] (Observe/run s n t)))


(def
  ^{:static true
    :arglists '([xf* flow])
    :doc "
Returns a discrete flow running given discrete `flow` and transforming values with the composition of given transducers `xf*`.

Cancelling propagates to upstream flow. Early termination by the transducing stage (via `reduced` or throwing) cancels upstream flow.

Example :
```clojure
(? (->> (seed (range 10))
        (eduction (filter odd?) (mapcat range) (partition-all 4))
        (reduce conj)))
#_=> [[0 0 1 2] [0 1 2 3] [4 0 1 2] [3 4 5 6] [0 1 2 3] [4 5 6 7] [8]]
```
"} eduction
  (fn e
    ([f] f)
    ([x f] (fn [n t] (Eduction/run x f n t)))
    ([x y & zs] (apply e (comp x y) zs))))

(def ^{:deprecated true
       :doc "Alias for `eduction`"}
  transform eduction)


(defn
  ^{:static true
    :arglists '([rf flow] [rf init flow])
    :doc "
Returns a discrete flow running given discrete `flow` and emitting given `init` value (or, if not provided, the result of calling `rf` with no argument) followed by successive reductions (by rf) of upstream values with previously emitted value.

Cancelling propagates to upstream flow. Early termination by `rf` (via `reduced` or throwing) cancels upstream flow.

Example :
```clojure
(? (->> [1 2 3 4 5]
        (seed)
        (reductions +)
        (reduce conj)))
#_=> [0 1 3 6 10 15]
```
"} reductions
  ([rf f] (fn [n t] (Reductions/run rf f n t)))
  ([rf i f] (reductions (fn ([] i) ([r x] (rf r x))) f)))

(def ^{:deprecated true
       :doc "Alias for `reductions`"}
  integrate reductions)


(defn
  ^{:static true
    :arglists '([flow])
    :doc "
Returns a `org.reactivestreams.Publisher` running given discrete `flow` on each subscription.
"} publisher [f]
  #?(:clj (reify Publisher (subscribe [_ s] (Pub. f s)))
     :cljs (throw (js/Error. "Unsupported operation."))))


(defn
  ^{:static true
    :arglists '([pub])
    :doc "
Returns a discrete flow subscribing to given `org.reactivestreams.Publisher`.
"} subscribe [pub]
  #?(:clj (fn [n t] (Sub. pub n t))
     :cljs (throw (js/Error. "Unsupported operation."))))


(def
  ^{:static true
    :arglists '([rf flow])
    :doc "
Returns a continuous flow producing values emitted by given discrete `flow`, relieving backpressure. When upstream is faster than downstream, overflowed values are successively reduced with given function `rf`.

Cancelling propagates to upstream. If `rf` throws, upstream `flow` is cancelled.

Example :
```clojure
;; Delays each `input` value by `delay` milliseconds
(defn delay-each [delay input]
  (ap (? (sleep delay (?> input)))))

(? (->> (ap (let [n (?> (seed [24 79 67 34 18 9 99 37]))]
              (? (sleep n n))))
        (relieve +)
        (delay-each 80)
        (reduce conj)))
#_=> [24 79 67 61 99 37]
```
"} relieve (fn [rf f] (fn [n t] (Relieve/run rf f n t))))


(defn
  ^{:static true
    :arglists '([capacity flow])
    :doc "
Returns a discrete flow producing values emitted by given discrete `flow`, accumulating upstream overflow up to `capacity` items.
"} buffer [c f]
  (assert (pos? c) "Non-positive buffer capacity.")
  (Buffer/flow c f))


(def
  ^{:static true
    :arglists '([f & flows])
    :doc "
Returns a flow running an arbitrary number of flows concurrently. The process is ready to transfer when at least one
input is ready to transfer. On transfer, all ready inputs are transferred, the function is called with the latest
value of each input and the result is returned. If an input emits consecutive values, all of them are transferred and
only the latest one is retained. Each input must be initially ready.

Input failures and exceptions thrown by `f` cancel the process and propagate the error. The process terminates when all
inputs are terminated. Cancelling the process cancels all inputs.

```clojure
(defn sleep-emit [delays]
  (reductions {} 0
    (ap (let [n (?> (seed delays))]
          (? (sleep n n))))))

(defn delay-each [delay input]
  (ap (? (sleep delay (?> input)))))

(? (->> (latest vector
          (sleep-emit [24 79 67 34])
          (sleep-emit [86 12 37 93]))
        (delay-each 50)
        (reduce conj)))

#_=> [[0 0] [24 0] [24 86] [79 12] [79 37] [67 37] [34 93]]
```
"} latest (fn [c & fs] (fn [n t] (Latest/run c fs n t))))


(def
  ^{:static true
    :arglists '([f sampled* sampler])
    :doc "
Returns a flow running an arbitrary number of sampled flows concurrently with a sampler flow. The process is ready to
transfer when the sampler is ready to transfer. On transfer, all ready inputs are transferred, the function is called
with the latest value of each input and the result is returned. If a sampled input emits consecutive values, all of
them are transferred and only the latest one is retained. Each sampled input must be initially ready.

When the sampler input terminates, all sampled inputs are cancelled. Input failures and exceptions thrown by `f` cancel
the process, and propagate the error. The process terminates when all input flows are terminated. Cancelling the process
cancels the sampler input.

Example :
```clojure
(defn sleep-emit [delays]
  (ap (let [n (?> (seed delays))]
        (? (sleep n n)))))

(defn delay-each [delay input]
  (ap (? (sleep delay (?> input)))))

(m/? (->> (m/sample vector
            (m/reductions {} 0 (sleep-emit [24 79 67 34]))
            (sleep-emit [86 12 37 93]))
       (delay-each 50)
       (m/reduce conj)))

#_=> [[24 86] [24 12] [79 37] [67 93]]
```
"} sample (fn [c f & fs] (fn [n t] (Sample/run c f fs n t))))


(defn
  ^{:static true
    :arglists '([f & flows])
    :doc "
Returns a discrete flow running given discrete `flows` concurrently and emitting the result of applying `f` to the set of first values emitted by each upstream flow, followed by the result of applying `f` to the set of second values and so on, until any upstream flow terminates, at which point the flow will cancel all other upstream flows and wait for their termination.

Cancelling propagates to every upstream flow. If any upstream flow fails or if `f` throws, the flow is cancelled.

Example :
```clojure
(m/? (->> (m/zip vector
                 (m/seed [1 2 3])
                 (m/seed [:a :b :c]))
          (m/reduce conj)))
#_=> [[1 :a] [2 :b] [3 :c]]
```
"} zip [c f & fs] (fn [n t] (Zip/run c (cons f fs) n t)))


(defn
  ^{:static true
    :arglists '([kf >f])
    :doc "
Returns a discrete flow running given discrete flow, calling given key function on each produced value, grouping values
according to keys returned by the function, and producing a key-group pair for each grouping found. A group is a flow
consuming values matching a key. Upstream values are dispatched in constant time to their group consumer.

Cancelling a group consumer makes it fail immediately. If a value is subsequently found for the same grouping, the
key-group pair is produced again, including in the special case where the consumer is cancelled while a transfer was
pending. Cancelling a group consumer has no effect when the main process is cancelled.

If upstream fails, or if the key function throws, then upstream is cancelled and flushed and the error is propagated
downstream.

When the last upstream value is consumed, downstream terminates along with each active consumer and subsequent ones.

Concurrent consumers on a single group are not allowed, attempting to do so will fail the latest consumer.

Example :
```clojure
(def words [\"Air\" \"Bud\" \"Cup\" \"Awake\" \"Break\" \"Chunk\" \"Ant\" \"Big\" \"Check\"])
(def groups
  (m/ap (let [[k >x] (m/?= (m/group-by (juxt first count) (m/seed words)))]
          [k (m/? (m/reduce conj >x))])))
(m/? (m/reduce conj {} groups))
#_=> {[\\C 3] [\"Cup\"],
      [\\B 5] [\"Break\"],
      [\\A 5] [\"Awake\"],
      [\\B 3] [\"Bud\" \"Big\"],
      [\\A 3] [\"Air\" \"Ant\"],
      [\\C 5] [\"Chunk\" \"Check\"]}
```
"} group-by [kf f] (fn [n t] (GroupBy/run kf f n t)))


(def
  ^{:static true
    :arglists '([boot])
    :doc "
Experimental. To be deprecated in favor of [lazy publishers](https://github.com/leonoel/missionary/issues/70)
"} reactor-call (fn [i] (fn [s f] (Reactor/run i s f))))


(defmacro
  ^{:arglists '([& body])
    :doc "
Calls `reactor-call` with a function evaluating given `body` in an implicit `do`.
"} reactor [& body] `(reactor-call (fn [] ~@body)))


(def
  ^{:static true
    :arglists '([flow])
    :doc "
Spawns a discrete publisher from given flow, see `reactor-call`.
"} stream! (fn [f] (Reactor/publish f false)))


(def
  ^{:static true
    :arglists '([flow])
    :doc "
Spawns a continuous publisher from given flow, see `reactor-call`.
"} signal! (fn [f] (Reactor/publish f true)))
