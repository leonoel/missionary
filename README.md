# Missionary – a functional effect and streaming system for Clojure/Script

Missionary is an < asynchronous / structured concurrency / functional programming / reactive dataflow programming > toolkit of composable, referentially transparent primitives for async IO actions, lazy continuous signals and eager discrete streams. Missionary is directly comparable to Rx, [ZIO](https://github.com/zio/zio), [Cats Effect](https://github.com/typelevel/cats-effect), [FS2](https://github.com/typelevel/fs2), Haskell's [Reflex](https://github.com/reflex-frp/reflex), OCaml's [Incremental](https://github.com/janestreet/incremental) and has some overlap with core.async.

Missionary's goals are:
* reduce accidental complexity in web programming, especially sophisticated real-time collaborative applications
* unification of functional effect systems and FRP / dataflow programming, which is not obvious
* Promote functional effects as the default Clojure concurrency model. [Task](https://github.com/leonoel/task) and [Flow](https://github.com/leonoel/flow) are abstract interfaces for this reason.
* missionary is partly born out of frustrations with Rx, it aims to be strictly more powerful and I'm not aware of any use case that is not covered yet

```clojure
(require '[missionary.core :as m])

; a task is a value of an async recipe for an effect
(def hello-task (m/sp (println "Hello") 42))
(hello-task #(print ::success %) #(print ::failure %))
; hello
; => 42

(def nap-task (m/sleep 1000)) ; effect value for async sleep

; async sequential composition of effects
; no callbacks, pure functional, composed pipeline is a value
; transparent propagation of termination and failure to entrypoint
(def async-hello-task
  (m/sp
    (m/? hello-task)
    (m/? nap-task)
    (println "World")
    (m/? nap-task)
    (println "!")))

(def cancel (async-hello-task #(print ::success %) #(print ::failure %)))
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

# Community

[#missionary](https://app.slack.com/client/T03RZGPFR/CL85MBPEF) on Clojurians slack

# Quick start

```clojure
(ns example
  (:require [missionary.core :as m]
            [hyperfiddle.rcf :refer [tests ! %]]))

(tests 
  "Task"
  (def task (m/sleep 1000))
  (def cancel (task (fn success [v] (prn :success v))
                    (fn failure [e] (prn :failure e))))
  % := _

  "monad-like sequential composition with dynamic control flow"
  (def task
    (m/sp
      (let [a (m/? (m/sleep 1000 42))]
        (m/? (if (odd? a)
               (m/sleep 1000 a)
               (m/sleep 1000 (inc a)))))))

  "concurrent tasks (running in parallel)"
  (let [x-task (m/sleep 1000 :x)]
    ; tasks are pure values, thus reusable (promises are not)
    (! (m/join vector x-task x-task)))
  ; 1000 ms sleep, not 2000
  % := [:x :x]
  
  "event stream"
  
  "dataflow diamond, an incremental DAG with shared node"
  (def a (atom 1))
  (def app
    (m/reactor
      (let [>a (m/signal! (m/watch a))
            >b (m/signal! (m/latest + >a >a))]
        (m/stream! (m/ap (println "v:" (! (m/?> >b))))))))
  (def dispose (app prn prn))                       
  ; v: 2
  % := 2
  (swap! a inc)                          
  ; "v: 4"
  % := 4
  ; A glitch-free engine writes 2 then 4, Rx would write 2, 3, then 4
  (dispose))
```

# References
* Nathaniel J Smith's posts on structured concurrency
