(ns missionary.core
  (:refer-clojure :exclude [reduce reductions eduction group-by])
  (:require [missionary.impl :as i]
            [cloroutine.core :refer [cr] :include-macros true])
  (:import (missionary.impl Reduce Reductions GroupBy Relieve))
  #?(:cljs (:require-macros [missionary.core :refer [sp ap amb> amb= ?? ?! holding reactor]])))


(def
  ^{:static true
    :doc "A `java.util.concurrent.Executor` optimized for blocking evaluation."}
  blk i/blk)


(def
  ^{:static true
    :doc "A `java.util.concurrent.Executor` optimized for non-blocking evaluation."}
  cpu i/cpu)


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
"} via-call [e t] (fn [s f] (i/thunk e t s f)))


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
  ([d x] (fn [s f] (i/sleep d x s f))))


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
  ([c] (fn [s _] (s (c)) i/nop))
  ([c & ts] (fn [s f] (i/race-join false c ts s f))))


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
  ([] (fn [_ f] (f (race-failure)) i/nop))
  ([& ts] (fn [s f] (i/race-join true race-failure ts s f))))


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
  (fn [s f] (task (i/absolver s f) f)))


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


(defn
  ^{:static true
    :arglists '([])
    :doc "
Throws an exception if current computation is required to terminate, otherwise returns nil.

Inside a process block, checks process cancellation status.

Outside of process blocks, fallbacks to thread interruption status if host platform supports it.
"} ! [] (i/fiber-poll))


(defn
  ^{:arglists '([task])
    :doc "
Runs given task, waits for completion and returns result (or throws, if task fails).

Inside a process block, parks the process.

Outside of process blocks, fallbacks to thread blocking if host platform supports it.
"} ? [t] (i/fiber-task t))


(defn
  ^{:arglists '([flow])
    :doc "
In an ambiguous process block, runs given `flow` non-preemptively (aka concat), forking process for each emitted value.

Example :
```clojure
(? (reduce conj (ap (inc (?> (seed [1 2 3]))))))
#_=> [2 3 4]
```
"} ?> [f] (i/fiber-flow-concat f))

(defmacro ^{:deprecated true
            :doc "Alias for `?>`"}
  ?? [f] `(?> ~f))


(defn
  ^{:arglists '([flow])
    :doc "
In an ambiguous process block, runs given `flow` preemptively (aka switch), forking process for each emitted value. Forked process is cancelled if `flow` emits another value before it terminates.

Example :
```clojure
(defn debounce [delay flow]
  (ap (let [x (?< flow)]
    (try (? (sleep delay x))
         (catch Exception _ (?> none))))))

(? (->> (ap (let [n (?> (seed [24 79 67 34 18 9 99 37]))]
              (? (sleep n n))))
        (debounce 50)
        (reduce conj)))
```
#_=> [24 79 9 37]
"} ?< [f] (i/fiber-flow-switch f))

(defmacro ^{:deprecated true
            :doc "Alias for `?<`"}
  ?! [f] `(?< ~f))


(defn
  ^{:arglists '([flow])
    :doc "
In an ambiguous process block, runs given `flow` and concurrently forks current process for each value produced by the flow. Values emitted by forked processes are gathered and emitted as soon as available.

Example :
```clojure
(? (->> (m/ap
          (let [x (m/?= (m/seed [19 57 28 6 87]))]
            (m/? (m/sleep x x))))
        (reduce conj)))

#_=> [6 19 28 57 87]
```
"} ?= [f] (i/fiber-flow-gather f))


(defmacro
  ^{:arglists '([& body])
    :doc "
Returns a task evaluating `body` (in an implicit `do`). Body evaluation can be parked with `?`.

Cancelling an `sp` task triggers cancellation of the task it's currently running, along with all tasks subsequently run.
"} sp [& body]
  `(partial (cr {? i/fiber-unpark} ~@body) i/sp))


(defmacro
  ^{:arglists '([& body])
    :doc "
Returns a flow evaluating `body` (in an implicit `do`) and producing values of each subsequent fork. Body evaluation can be parked with `?` and forked with `?>`, `?<` and `?=`.

Cancelling an `ap` flow triggers cancellation of the task/flow it's currently running, along with all tasks/flows subsequently run.
"} ap [& body]
  `(partial (cr {?  i/fiber-unpark
                 ?> i/fiber-unpark
                 ?< i/fiber-unpark
                 ?= i/fiber-unpark}
              ~@body) i/ap))


(defn
  ^{:static true
    :arglists '([task])
    :doc "
Inhibits cancellation signal of given `task`.
"} compel [task]
  (fn [s f] (task s f) i/nop))


(defn
  ^{:static true
    :arglists '([])
    :doc "
Creates an instance of dataflow variable (aka single-assignment).

A dataflow variable is a function implementing `assign` on 1-arity and `deref` on 2-arity (as task). `assign` immediately binds the variable to given value if not already bound and returns bound value. `deref` is a task completing with the value bound to the variable as soon as it's available.

Cancelling a `deref` task makes it fail immediately.
```
"} dfv [] (i/dataflow))


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
"} mbx [] (i/mailbox))


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
"} rdv [] (i/rendezvous))


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
  ([n] (i/semaphore n)))


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
  (fn [_ f] (i/never f)))


(def
  ^{:static true
    :doc "
The empty flow. Doesn't produce any value and terminates immediately. Cancelling has no effect.

Example :
```clojure
(? (reduce conj none))
#_=> []
```
"} none (fn [_ t] (t) i/nop))


(defn
  ^{:static true
    :arglists '([collection])
    :doc "
Returns a discrete flow producing values from given `collection`. Cancelling before having reached the end makes the flow fail immediately.
"} seed [coll]
  (fn [n t] (i/enumerate coll n t)))

(def ^{:deprecated true
       :doc "Alias for `seed`"}
  enumerate seed)


(defmacro
  ^{:arglists '([& forms])
    :doc "In an `ap` block, evaluates each form sequentially and returns successive results."}
  amb> [& forms]
  `(case (?> (seed (range ~(count forms))))
     ~@(interleave (range) forms)))


(defmacro
  ^{:arglists '([& forms])
    :doc "In an `ap` block, evaluates each form concurrently and returns results in order of availability."}
  amb= [& forms]
  `(case (?= (seed (range ~(count forms))))
     ~@(interleave (range) forms)))


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
Returns a continuous flow producing successive values of given `reference` until cancelled. Given reference must support `add-watch`, `remove-watch` and `deref`. Oldest values are discarded on overflow.
"} watch [r] (fn [n t] (i/watch r n t)))


(defn
  ^{:static true
    :arglists '([subject])
    :doc "
Returns a discrete flow observing values produced by a non-backpressured subject until cancelled. `subject` must be a function taking a 1-arity `event` function and returning a 0-arity `cleanup` function.

`subject` function is called on initialization. `cleanup` function is called on cancellation. `event` function may be called at any time, it throws an exception on overflow and becomes a no-op after cancellation.
"} observe [s] (fn [n t] (i/observe s n t)))


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
    ([x f] (fn [n t] (i/transform x f n t)))
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
"} publisher [flow] (i/publisher flow))


(defn
  ^{:static true
    :arglists '([pub])
    :doc "
Returns a discrete flow subscribing to given `org.reactivestreams.Publisher`.
"} subscribe [pub] (fn [n t] (i/subscribe pub n t)))


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
  (fn [n t] (i/buffer c f n t)))


(defn
  ^{:static true
    :arglists '([f & flows])
    :doc "
Returns a continuous flow running given continuous `flows` in parallel and combining latest value of each flow with given function `f`.

```clojure
(defn sleep-emit [delays]
  (ap (let [n (?> (seed delays))]
        (? (sleep n n)))))

(defn delay-each [delay input]
  (ap (? (sleep delay (?> input)))))

(? (->> (latest vector
                (sleep-emit [24 79 67 34])
                (sleep-emit [86 12 37 93]))
        (delay-each 50)
        (reduce conj)))

#_=> [[24 86] [24 12] [79 37] [67 37] [34 93]]
```
"} latest
  ([f] (ap (f)))
  ([f & fs] (fn [n t] (i/latest f fs n t))))


(defn
  ^{:static true
    :arglists '([f sampled sampler])
    :doc "
Returns a discrete flow running given `sampler` discrete flow and `sampled` continuous flow in parallel. For each `sampler` value, emits the result of function `f` called with current values of `sampled` and `sampler`.

Cancellation propagates to both flows. When `sampler` terminates, `sampled` is cancelled. A failure in any of both flows, or `f` throwing an exception, or trying to pull a value before first value of `sampled` will cancel the flow and propagate the error.

Example :
```clojure
(defn sleep-emit [delays]
  (ap (let [n (?> (seed delays))]
        (? (sleep n n)))))

(defn delay-each [delay input]
  (ap (? (sleep delay (?> input)))))

(? (->> (sample vector
                (sleep-emit [24 79 67 34])
                (sleep-emit [86 12 37 93]))
        (delay-each 50)
        (reduce conj)))

#_=> [[24 86] [24 12] [79 37] [67 93]]
```
"} sample [f sd sr] (fn [n t] (i/sample f sd sr n t)))


(defn
  ^{:deprecated true
    :static true
    :arglists '([& flows])
    :doc "
Returns a discrete flow running given discrete `flows` in parallel and emitting upstream values unchanged, as soon as they're available, until every upstream flow is terminated.

Cancelling propagates to every upstream flow. If any upstream flow fails, the flow is cancelled.

Example :
```clojure
(? (->> (gather (seed [1 2 3])
                (seed [:a :b :c]))
        (reduce conj)))
#_=> [1 :a 2 :b 3 :c]
```
"} gather
  ([] none)
  ([f] f)
  ([f & fs] (ap (?> (?= (seed (cons f fs)))))))


(defn
  ^{:static true
    :arglists '([f & flows])
    :doc "
Returns a discrete flow running given discrete `flows` is parallel and emitting the result of applying `f` to the set of first values emitted by each upstream flow, followed by the result of applying `f` to the set of second values and so on, until any upstream flow terminates, at which point the flow will be cancelled.

Cancelling propagates to every upstream flow. If any upstream flow fails or if `f` throws, the flow is cancelled.

Example :
```clojure
(m/? (->> (m/zip vector
                 (m/seed [1 2 3])
                 (m/seed [:a :b :c]))
          (m/reduce conj)))
#_=> [[1 :a] [2 :b] [3 :c]]
```
"} zip [c f & fs] (fn [n t] (i/zip c (cons f fs) n t)))

(defn
  ^{:static true
    :arglists '([kf >f])
    :doc "
Returns a discrete flow running given discrete flow, calling given key function on each produced value, grouping values
according to keys returned by the function, and producing a key-group pair for each grouping found. A group is a flow
consuming values matching a key. Upstream values are dispatched in constant time to their group consumer.

Cancelling a group consumer makes it fail immediately. If a value is subsequently found for the same grouping, the
key-group pair is produced again, including in the special case where the consumer is cancelled while a transfer was
pending.

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

(defn
  ^{:static true
    :arglists '([boot])
    :doc "
Returns a task spawning a reactor context with given boot function, called without argument. A reactor context manages
the lifecycle of running flows and serializes their emissions in propagation turns. Flows running in a reactor context
are called publishers, they can be spawned in the boot function or in reaction to any subsequent emission with `stream!`
or `signal!`, respectively for discrete and continuous flows. Publishers of a given reactor context are totally ordered
by the union of two partial orders :
* if a publisher was created by another one, the parent is inferior to the child.
* if two publishers are siblings, the older is inferior to the younger.

A publisher can subscribe to the feed of an inferior publisher from the same reactor context. Using a publisher as a
flow spawns a subscription to this publisher. Publisher emissions are grouped in propagation turns, where successive
publishers of a given turn are strictly increasing. When a publisher becomes able to emit, it is compared to the
publisher currently emitting to figure out whether the emission must happen on the current turn or on the next one.
Cyclic reactions are possible and no attempt is made to prevent them, so care must be taken to ensure the propagation
eventually stops. The dispatching mechanism depends on the nature of the publisher, discrete or continuous.
* a stream is able to emit when its flow is ready to transfer and the backpressure of the previous emission has been
collected from all of its subscriptions. On emission, a value is transferred from the flow and subscriptions are
notified of the availability of this value. Until the end of the propagation turn, each new subscription to this stream
will be immediately notified.
* a signal always has a current value, thus each new subscription is immediately notified. It is able to emit when its
flow is ready to transfer. On emission, its current value is marked as stale and its subscriptions are notified if not
already. From this point, a sampling request from any of its subscriptions will trigger the transfer from the flow to
refresh the current value. Subsequent sampling requests will reuse this memoized value until next emission.

A subscription terminates when it's cancelled or when the underlying publisher terminates. A publisher can be cancelled,
as long as its flow is not terminated, by calling it as a zero-argument function. A cancelled publisher cancels its
flow, transfers and discards all of its remaining values without backpressure, and all of its current and future
subscriptions fail immediately. Cancelling a reactor cancels all of its publishers, and any subsequent publisher
spawning will fail. If any publisher flow fails, or if the boot function throws an exception, the reactor is cancelled.
A reactor terminates at the end of the first turn where every publisher flow is terminated, meaning no emission can
ever happen anymore. It succeeds with the result of the boot function if no publisher failed, otherwise it fails with
the first error encountered.
"} reactor-call [i] (fn [s f] (i/context i s f)))


(defmacro
  ^{:arglists '([& body])
    :doc "
Calls `reactor-call` with a function evaluating given `body` in an implicit `do`.
"} reactor [& body] `(reactor-call (fn [] ~@body)))


(defn
  ^{:static true
    :arglists '([flow])
    :doc "
Spawns a discrete publisher from given flow, see `reactor-call`.
"} stream! [f] (i/publish f true))


(defn
  ^{:static true
    :arglists '([flow])
    :doc "
Spawns a continuous publisher from given flow, see `reactor-call`.
"} signal! [f] (i/publish f false))
