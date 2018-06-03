# Missionary

`missionary` is an asynchronous programming toolkit for clojure and clojurescript, providing non-blocking synchronization primitives, coroutine syntax for sequential logic, parallel composition operators and timing helpers. All asynchronous operations rely on [task](https://github.com/leonoel/task) semantics.


## Status

Maturity : alpha. Breaking changes should be expected.

Artifacts are released to [clojars](https://clojars.org/missionary).

Current coordinates : `[missionary "a.1"]`


## Rationale

Concurrency is hard, there is still no silver bullet and is unlikely to be any in a near future. `missionary` is an attempt to unify various concurrency primitives under the same protocol in order to allow arbitrary composition no matter what the underlying semantics.

`missionary`'s sequential processes are heavily inspired by `core.async`'s `go`, both in design and implementation. The coroutine syntax provides tremendous value to define sequential logic, having arguably more expressive power than monadic chaining. However, relying on channel semantics as the universal synchronization primitive provides very little help to deal with additional control flow introduced by concurrency : `go` blocks must adopt a defensive strategy against uncaught errors because channels are unable to propagate them to the caller, and special care must be taken to ensure each process is properly supervised.

Instead, asynchronous boundaries in `missionary` rely on an unopinionated interface supporting failure and cancellation out of the box, and execution of sequential processes is separated from their declaration, allowing supervision to be performed by reusable constructs.

Unlike `core.async`, `missionary` (currently) provides no selective await (à la `alt!`), because this significantly complects design and provides little value, most use cases being covered by cancellation and parallel composition.


## Documentation

Single API namespace : `missionary.core`

```clojure
(require '[missionary.core :as m])
```


### Blocking operations

`off` macro takes a body of expressions and returns a task evaluating it on an unbounded thread pool, effectively turning a blocking effect into a non-blocking one.

Interoperability with blocking APIs must be done this way because the task composition machinery expects all work to be cpu-bound.

Cancelling an `off` task execution will interrupt the thread running the task.

```clojure
(def get-clojure (m/off (slurp "https://clojure.org")))
```

`?` function blocks calling thread until execution of given task is completed. If thread is interrupted awaiting result, pending task will be cancelled.

Blocking threads like this should only be done during REPL experimentation, or at the edges of the application (ie not far from the main or the shutdown hooks).

```clojure
(m/? get-clojure)                  ;; => performs request, waiting for response.
```

Blocking operations are not supported in clojurescript due to host platform limitations.


### Timing

`sleep` function returns a task completing with given value (nil if not provided) after given duration (in milliseconds). Cancelling a sleep task makes it fail immediately.

```clojure
(m/? (m/sleep 1000 42))            ;; => returns 42 after 1 second
```

`timeout` function returns a task executing given task, cancelling it if not completed within given duration (in milliseconds).

```clojure
(m/? (m/timeout 100 get-clojure)) ;; => returns response if request takes less than 100 ms, else throws exception
```


### Parallel composition

`join` function takes an arbitrary number of tasks and returns a task executing them concurrently, completing with the vector of results unless any of them fails.

When the first failure happens, parent task will fail after having pending tasks cancelled and awaited for completion, discarding results.

Cancellation signal on parent task is propagated to children tasks.

```clojure
(m/? (m/join (m/sleep 1000 1) (m/sleep 1000 2)))   ;; returns [1 2] after 1 second
```

`race` function takes an arbitrary number of tasks and returns a task executing them concurrently, completing with the first successful result unless all of them fails.

When the first success happens, parent task will succeed after having pending tasks cancelled and awaited for completion, discarding results.

Cancellation signal on parent task is propagated to children tasks.

```clojure
(m/? (m/race (m/sleep 1000 1) (m/sleep 2000 2)))   ;; returns 1 after 1 second
```


### Sequential processes

`sp` macro defines a sequential process, taking a body and returning a task evaluating it when executed.

```clojure
(def roll-dice (m/sp (inc (rand-int 6))))
(def kill-math (m/sp (/ 1 0)))
(m/? roll-dice)             ;; returns a random number between 1 and 6
(m/? roll-dice)             ;; returns another random number between 1 and 6
(m/? kill-math)             ;; throws ArithmeticException
```

Inside an `sp` block, `?` will execute given task and park process until result is available.
```clojure
(m/? (m/sp (m/? (m/sleep 1000))
           (m/? roll-dice)))     ;; returns a random number between 1 and 6 after 1 second
```

A failed task execution will propagate its error along `sp` block, following try/catch semantics.
```clojure
(m/? (m/sp (try (m/? kill-math)
                (catch Exception _ :math-is-fine))))       ;; returns :math-is-fine
```

Cancelling an `sp` task will cancel the execution of the task it's currently waiting for, and all tasks subsequently executed will be immediately cancelled.

```clojure
(m/? (m/timeout 1000 (m/sp (m/? (m/sleep 2000)))))  ;; throws exception after 1 second
```

Cancellation status of an `sp` task can be checked with the 0-arity of `?`, throwing an exception if termination has been requested, else returning nil.

```clojure
(m/? (m/timeout 0 (m/sp (while true (m/?)))))       ;; throws exception
```


### Dataflow variables

`dfv` function creates a dataflow (single-assignment) variable.

Returned object is a function implementing assignment on 1-arity and dereference on 2-arity. Assignment immediately binds the variable to given value if not already bound and returns nil. Dereference is a task completing with the value bound to the variable as soon as it's available.

Cancelling a dereferencing task makes it fail immediately.

Example : A future is an eager and memoized view of a task, relying on a dataflow variable to store its result.
```clojure
(defn future! [task]
  (let [result (m/dfv)
        cancel (task #(result (fn [! _] (! %)))
                     #(result (fn [_ !] (! %))))]
    (fn
      ([] (cancel))
      ([s! f!] (result #(% s! f!) f!)))))

(def response (future! get-clojure))        ;; eagerly performs request
(m/? response)                              ;; returns response when ready
(m/? response)                              ;; returns memoized response immediately
```


### Buffers

`buf` function creates a non-backpressured buffer.

Returned object is a function implementing enqueue on 1-arity and dequeue on 2-arity. Enqueue immediately appends given value to buffer and returns nil. Dequeue is a task removing the first value from buffer as soon as it's non-empty and completing with this value.

Cancelling a dequeuing task makes it fail immediately.

Example : an actor is a buffer associated with a process consuming messages.
```clojure
(defn crash [^Throwable e]                                ;; let it crash philosophy
  (.printStackTrace e)
  (System/exit -1))

(defn actor
  ([init] (actor init crash))
  ([init fail]
   (let [self (m/buf)]
     ((m/sp
        (loop [b init]
          (recur (b self (m/? self)))))
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


### Synchronous rendez-vous

`rdv` function creates a synchronous rendez-vous.

Returned object is a function implementing give on its 1-arity and take on its 2-arity. Give takes the value to transfer and returns a task completing with nil as soon as a taker is available. Take is a task completing with transferred value as soon as a giver is available.

Cancelling giving and taking tasks makes them fail immediately.

Example : producer / consumer stream communication
```clojure
(defn reducer [rf i take]
  (m/sp
    (loop [r i]
      (let [x (m/? take)]
        (if (identical? x take)
          r (recur (rf r x)))))))

(defn iterator [give xs]
  (m/sp
    (loop [xs (seq xs)]
      (if-some [[x & xs] xs]
        (do (m/? (give x))
            (recur xs))
        (m/? (give give))))))

(def stream (m/rdv))

(second (m/? (m/join (iterator stream (range 100)) (reducer + 0 stream))))      ;; returns 4950
```


### Semaphores

`sem` function creates a semaphore initialized with n tokens (1 if not provided, aka mutex).

Returned object is a function implementing release on 0-arity and acquire on 2-arity. Release immediately makes a token available and returns nil. Acquire is a task completing with nil as soon as a token is available.

Cancelling an acquiring task makes it fail immediately.

`holding` macro acquires given semaphore and evaluates body, ensuring semaphore is released after evaluation.

Example : dining philosophers
```clojure
(defn phil [name f1 f2]
  (m/sp
    (while true
      (prn name :thinking)
      (m/? (m/sleep 500))
      (m/holding f1
        (m/holding f2
          (prn name :eating)
          (m/? (m/sleep 600)))))))

(def forks (vec (repeatedly 5 m/sem)))

(m/? (m/timeout 10000
       (m/join (phil "descartes" (forks 0) (forks 1))
               (phil "hume"      (forks 1) (forks 2))
               (phil "plato"     (forks 2) (forks 3))
               (phil "nietzsche" (forks 3) (forks 4))
               (phil "kant"      (forks 0) (forks 4)))))
```


## License

Licensed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html) (the same as Clojure)
