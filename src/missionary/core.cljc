(ns missionary.core
  (:require [missionary.impl :as i]))

(defmacro
  ^{:arglists '([& body])
    :doc "Returns a task evaluating body in an unbounded thread pool and completing with its result.
Cancelling an off task will interrupt the thread running it."}
  off [& args] (cons `i/off args))

(def
  ^{:arglists '([] [task])
    :doc "0-arity : throws an exception if termination has been requested, else returns nil. Checks task cancellation inside sp blocks, thread interruption outside.
1-arity : executes given task and waits for its completion. Parks process inside sp blocks, blocks thread outside."}
  ? i/?)

(def
  ^{:arglists '([duration] [duration value])
    :doc "Returns a task completing with given value (nil if not provided) after given duration (in milliseconds).
Cancelling a sleep task makes it fail immediately."}
  sleep i/sleep)

(def
  ^{:arglists '([duration task])
    :doc "Returns a task executing given task, cancelling it if not completed within given duration (in milliseconds)."}
  timeout i/timeout)

(def
  ^{:arglists '([& tasks])
    :doc "Takes an arbitrary number of tasks and returns a task executing them concurrently, completing with the vector of results unless any of them fails.
When the first failure happens, parent task will fail after having pending tasks cancelled and awaited for completion, discarding results.
Cancellation signal on parent task is propagated to children tasks."}
  join i/join)

(def
  ^{:arglists '([& tasks])
    :doc "Takes an arbitrary number of tasks and returns a task executing them concurrently, completing with the first successful result unless all of them fails.
When the first success happens, parent task will succeed after having pending tasks cancelled and awaited for completion, discarding results.
Cancellation signal on parent task is propagated to children tasks."}
  race i/race)

(defmacro
  ^{:arglists '([& body])
    :doc "Returns a task evaluating body, which must be cpu-bound, and completing with its result.
Cancelling an sp task will cancel the execution of the task it's currently waiting for, and all tasks subsequently executed will be immediately cancelled."}
  sp [& args] (cons `i/sp args))

(def
  ^{:arglists '([task])
    :doc "Prevents given task from cancellation."}
  compel i/compel)

(def
  ^{:arglists '([])
    :doc "Creates a dataflow (single-assignment) variable.
Returned object is a function implementing assignment on 1-arity and dereference on 2-arity.
Assignment immediately binds the variable to given value if not already bound and returns nil.
Dereference is a task completing with the value bound to the variable as soon as it's available.
Cancelling a dereferencing task makes it fail immediately."}
  dfv i/dfv)

(def
  ^{:arglists '([])
    :doc "Creates a non-backpressured buffer.
Returned object is a function implementing enqueue on 1-arity and dequeue on 2-arity.
Enqueue immediately appends given value to buffer and returns nil.
Dequeue is a task removing the first value from buffer as soon as it's non-empty and completing with this value.
Cancelling a dequeuing task makes it fail immediately."}
  buf i/buf)

(def
  ^{:arglists '([])
    :doc "Creates a synchronous rendez-vous.
Returned object is a function implementing give on its 1-arity and take on its 2-arity.
Give takes the value to transfer and returns a task completing with nil as soon as a taker is available.
Take is a task completing with transferred value as soon as a giver is available.
Cancelling giving and taking tasks makes them fail immediately."}
  rdv i/rdv)

(def
  ^{:arglists '([] [n])
    :doc "Creates a semaphore initialized with n tokens (1 if not provided, aka mutex).
Returned object is a function implementing release on 0-arity and acquire on 2-arity.
Release immediately makes a token available and returns nil.
Acquire is a task completing with nil as soon as a token is available.
Cancelling an acquiring task makes it fail immediately."}
  sem i/sem)

(defmacro
  ^{:arglists '([sem & body])
    :doc "Acquires given semaphore and evaluates body, ensuring semaphore is released after evaluation."}
  holding [& args] (cons `i/holding args))
