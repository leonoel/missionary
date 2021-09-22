# Missionary – a functional effect and streaming system for Clojure/Script

Missionary is an < asynchronous / structured concurrency / functional programming / reactive dataflow programming > toolkit of composable, referentially transparent primitives for async IO actions, lazy continuous signals and eager discrete streams. Missionary is directly comparable to Rx, [ZIO](https://github.com/zio/zio), [Cats Effect](https://github.com/typelevel/cats-effect), [FS2](https://github.com/typelevel/fs2), Haskell's [Reflex](https://github.com/reflex-frp/reflex), OCaml's [Incremental](https://github.com/janestreet/incremental) and has some overlap with core.async.

Missionary's goals are:
* reduce accidental complexity in web programming, especially sophisticated real-time collaborative applications
* unification of functional effect systems and FRP / dataflow programming, which is not obvious
* Promote functional effects as the default Clojure concurrency model. [Task](https://github.com/leonoel/task) and [Flow](https://github.com/leonoel/flow) are abstract interfaces for this reason.
* missionary is partly born out of frustrations with Rx, it aims to be strictly more powerful and I'm not aware of any use case that is not covered yet

```clojure
(require '[missionary.core :as m])

; a task is an effect as a value, aka "IO action" or "recipe"
(def hello-task (m/sp (println "Hello") (m/? (m/sleep 1000)) 42))
(def cancel! (hello-task #(println ::success %) #(println ::failure %)))
; Hello
; ::success 42

; async sequential composition of effects
; no callbacks, pure functional, composed pipeline is a value
; transparent propagation of termination and failure to entrypoint
(def async-hello-task
  (m/sp
    (let [x (m/? hello-task)]   ; run task value and await result
      (m/? hello-task)          ; reuse same task value
      (println "World!")
      x)))

(def cancel! (async-hello-task #(println ::success %) #(println ::failure %)))
; Hello
(cancel!)
; ::failure #error {:cause "Sleep cancelled."}
```

# Features
* Clojure/Script
* tasks (promise-like IO actions)
* eager discrete event streams with backpressure
* lazy continuous signals with lazy sampling (work-skipping)
* strict supervision (cancellation, termination, failure)
* strong resource cleanup guarantees
* lispy IOC syntax in lieu of monadic composition
* null handling

# Dependency

Project status: stable

```clojure
{:deps {missionary/missionary {:mvn/version "b.20"}}} 
```
[![clojars](https://img.shields.io/clojars/v/missionary.svg)](https://clojars.org/missionary)
[![cljdoc](https://cljdoc.org/badge/missionary/missionary)](https://cljdoc.org/d/missionary/missionary/CURRENT)
[![build](https://travis-ci.org/leonoel/missionary.svg?branch=master)](https://travis-ci.org/leonoel/missionary)
[![license](https://img.shields.io/github/license/leonoel/missionary.svg)](LICENSE)

# Applications
* general purpose async for applications
* stream processing in applications
* incremental computations (spreadsheets, reactjs-like UI rendering)
* React.js-like rendering (incremental maintenance of vdom, or fine-grained effects against live dom)
* streaming client/server web datasync
* sophisticated concurrency algorithms
* NOT an OOTB framework for distributed computation, but a toolkit for making that

Projects using Missionary
* hyperfiddle/hfql – a streaming datomic pull evaluator
* hyperfiddle/photon – a reactive, distributed dialect of Clojure/Script
* hyperfiddle/zero – library for real-time client/server web applications with zero boilerplate, implemented in Photon

# Documentation

API Reference: [`missionary.core`](https://cljdoc.org/d/missionary/missionary/CURRENT/api/missionary.core)

Tutorials
1. [Hello task](doc/tutorials/hello_task.md)
2. [Hello flow](doc/tutorials/hello_flow.md)
3. [Comparison to RxJava](doc/tutorials/rx_comparison.md)

Guides
1. [Retry with backoff](doc/guides/retry_backoff.md)
2. [Happy eyeballs](doc/guides/happy_eyeballs.md)
3. [Iterative queries](doc/guides/iterative_queries.md)

Comparison with core.async
* (Dustin) core.async is for decoupling a system into smaller independent process components separated by backpressured queues, like a web service
* (Dustin) Missionary is a toolkit for surgically precise control over rich and dynamic compositions of effects, enabling new kinds of application infrastructure that orchestrate fine-grained effects at scale and in a palatable, robust, and richly programmable way.

Problems with core.async
* no supervision hierarchy. each `go` block spawns a new process without keeping track of the link with parent process
* no interruption. To interrupt a go block and propagate interruption to its child processes, you have to implement that yourself
* no error handling
* channels are not values
* channels are not the right abstraction for most things

Problems with futures and promises
* state
* no cancellation
* bad syntax

Design notes
* Like Haskell, effects are values
* Every forked process must eventually be joined
* Every asynchronous operation must be cancellable
* Asynchronous design for efficiency and clojurescript compatibility
* Leverages standard clojure primitives (collections, transducers, reducing functions, reference types, first-class `nil`) to prevent combinatorial explosion of operators
* unified "Flow" protocol representing both discrete streams and continuous signals
* Simple, REPL-friendly, dependency-free underlying protocols: [task](https://github.com/leonoel/task) & [flow](https://github.com/leonoel/flow), respectively single and multiple value producers.
* seamless conversion between stream and signal through integration
* [Reactive Streams](http://www.reactive-streams.org/) compliant

Discussions
* https://clojureverse.org/t/missionary-new-release-with-streaming-support-design-notes/4510/7
* https://clojureverse.org/t/cloroutine-v1/3300/8
* https://www.reddit.com/r/Clojure/comments/k2db8k/leonoelmissionary_a_functional_effect_and/

# FAQ

*What is incremental programming?* – When you lift an expression into an incrementally maintained reactive context such that a small change in input will result in a small adjustment to the output, by reusing past intermediate results

*What is reactive programming?* – 

*What is a functional effect system?* –

*What is a streaming system?* –

*What is a structured concurrency framework?* –

*What is dataflow programming?* – dataflow programs can more or less be visualized as flowchart DAGs with functions on the edges. The flowchart is reactive like a spreadsheet, so if you have a cell C1 = F(A1, B1), if B1 changes, C1 will recompute and flow downstream. 

*What is lazy sampling?* –

*What is backpressure on an event stream?* –


# Prior art

## vs imperative
`missionary` promotes a functional approach to concurrency, focusing on computation instead of conveyance. It is deeply
impacting for the user because the objects implied in both cases have fundamentally different requirements : whereas
communication devices have indefinite scope and can be safely garbage collected, running processes are bounded in time
and must be supervised.

Popular conveyance-oriented techniques include clojure's succession model, CSP, futures/promises, actor systems. In
all of these programming models, the first-class primitive is a communication device (resp. reference types, channels,
dataflow variables, mailing addresses) used as an interface to coordinate encapsulated stateful processes. This low-level
programming style generally makes no attempt to provide any structure to concurrent computations, which means
supervision must be implemented in user space, generally as an afterthought, often simply omitted. Imperative
[structured concurrency](https://en.wikipedia.org/wiki/Structured_concurrency) is currently an active area of research.

Functional composition is fundamentally more constrained because it enforces a strict hierarchy of concurrent programs.
The benefit for the user is that supervision concerns don't leak to the domain, the runtime engine knows about the
program structure and therefore can endorse the right behavior in face of failure (cancel siblings and propagate error
to parent). Usual communication devices are still provided to cover use cases requiring data transfer across branches
of the supervision tree, but their usage is specialized instead of generalized.

## vs functional
[ReactiveX](http://reactivex.io) is one of the first functional effect system to have gained significant traction
in mainstream languages. Other popular incarnations of this paradigm can be found in the Scala ecosystem, namely
[Cats Effects](https://typelevel.org/cats-effect), [ZIO](https://zio.dev) and [Monix](https://monix.io), all heavily
influenced by haskell's IO monad.

`missionary` aims to make sequential composition more practical, dismissing monadic binding in favor of a DSL that is
a superset of the host language. This idea is by no means new, it is even rather popular nowadays and present in almost
every modern concurrency framework, including in clojure with [core.async](https://github.com/clojure/core.async)'s
`go` blocks. Ambiguous expressions are the natural extension of this technique to multiple value producers, but that
part has definitely not reached mainstream yet. Surprisingly enough, it is sparingly used even in the modern functional
programming landscape.

## vs reactive
While traditional streaming engines are focusing on discrete events, `missionary` is designed upfront to also support
continuous time, which is the realm of [FRP](https://en.wikipedia.org/wiki/Functional_reactive_programming). This is
made possible by the flow abstraction, a foundational protocol allowing a producer to signal availability of a value
without eagerly computing it, making suitable for both backpressured event streaming and lazy sampling of time-varying
values. This unified representation bridges the gap between functional and reactive programming into a consistent model
providing the best of both worlds.


# Community

[#missionary](https://app.slack.com/client/T03RZGPFR/CL85MBPEF) on Clojurians slack

# Quick start

```clojure
(ns readme2
  (:require [missionary.core :as m]
            [hyperfiddle.rcf :as rcf :refer [tests ! %]]))

(tests
  "async tests demo"
  ; rcf/! is like println except taps the value to a queue for checking
  (rcf/! :a)
  (! :b)
  % := :a
  % := :b
  % := ::rcf/timeout)

(tests
  "Task is a single-value producer (one-off async effect)"
  (def task (m/sp (println "Hello") (m/? (m/sleep 50)) (! 42)))
  (def cancel! (task (fn success [v] (println :success v))
                     (fn failure [e] (println :failure e))))
  % := 42)

(tests
  "Tasks are sequential composition (monad-like, async/await)"
  (def nap (m/sleep 10 42))
  (def task
    (m/sp
      (m/? (if (odd? (m/? nap))     ; runtime control flow
             (m/sleep 10 ::odd)
             (m/sleep 10 ::even)))))
  (def cancel! (task ! !))
  % := ::even)

(tests
  "concurrent tasks (running in parallel)"
  ; tasks are pure values, thus reusable (promises are not)
  (let [x-task (m/sleep 800 :x)]
    (! (m/? (m/join vector x-task x-task))))
  ; 800 ms sleep, not 1600
  % := [:x :x])

(tests
  "discrete flow (eager event stream with backpressure)")

(tests
  "continuous flow (lazy signal with work-skipping)")

(tests
  "integrate a discrete flow into a continuous flow by relieving backpressure")

(tests
  "dataflow diamond, an incremental DAG with shared node"
  (def a (atom 1))
  (def app
    (m/reactor
      (let [>a (m/signal! (m/watch a))
            >b (m/signal! (m/latest + >a >a))]
        (m/stream! (m/ap (! (m/?> >b)))))))
  (def dispose (app prn prn))
  % := 2
  (swap! a inc)
  % := 4
  ; A glitch-free engine emits 2 4, Rx would wrongly emit 2 3 4
  (dispose))

(tests 
  "hiccup incremental rendering with work-skipping")
```

# References
* Nathaniel J Smith's posts on structured concurrency
