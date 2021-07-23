# Missionary – a functional effect and streaming system for Clojure/Script

Missionary is a structured concurrency framework (for programming without callbacks), competitive with core.async. Missionary has referentially transparent primitives for IO actions, continuous signals and discrete streams.

* batteries-included concurrency toolkit
* alternative to IO monad
* reactive dataflow programming

```clojure
(require [missionary.core :as m])

; incremental view maintenance
(def !x (atom 0))
(def dispose
  ((m/reactor (m/stream! (m/ap (println (inc (m/?< (m/watch !x)))))))
   (fn [_] (prn ::done)) (fn [e] (prn ::crash e))))
; 1
(swap! !x inc)
; 2
(swap! !x inc)
; 3
(dispose)
; :done
```

Applications
* reactive programming
* React.js-like rendering (incremental maintenance of vdom)
* , or fine grained effects)
* sophisticated concurrency algorithms
* database incremental view maintenance
* implement a reactive database

Features
* Clojure/Script
* processes are values
* backpressure, cancellation, termination
* IOC syntax (not equivalent monadic composition)
* null handling
* dataflow primitives: eager discrete streams and lazy continuous signals
* Strict supervision providing transparent propagation of cancellation and failure, with strong resource cleanup guarantees.
* [Reactive Streams](http://www.reactive-streams.org/) compliant

# Dependency

Project status: stable

```clojure
{:deps {missionary/missionary {:mvn/version "b.20"}}} 
```
[![clojars](https://img.shields.io/clojars/v/missionary.svg)](https://clojars.org/missionary)
[![cljdoc](https://cljdoc.org/badge/missionary/missionary)](https://cljdoc.org/d/missionary/missionary/CURRENT)
[![build](https://travis-ci.org/leonoel/missionary.svg?branch=master)](https://travis-ci.org/leonoel/missionary)
[![license](https://img.shields.io/github/license/leonoel/missionary.svg)](LICENSE)

# Usage

```clojure
(ns example
  (:require [missionary.core :as m]
            [hyperfiddle.rcf :refer [tests ! %]]))

(tests 
  "Task"

  "Flow"
  
  "Stream"
  
  "Signal"
  
  "incremental view maintenance"
  (def !x (atom 0))
  (def dispose ((m/reactor (m/stream! (m/ap (! (inc (m/?< (m/watch !x)))))))
                (fn [_] #_(prn ::done)) #(prn ::crash %)))
  % := 1
  (swap! !x inc)
  (swap! !x inc)
  % := 2
  % := 3
  (dispose))







```

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

Problems with core.async
* no supervision hierarchy. each `go` block spawns a new process without keeping track of the link with parent process
* no interruption. To interrupt a go block and propagate interruption to its child processes, you have to implement that yourself
* no error handling
* channels are not values
* channels are not the right abstraction for most things

Problems with futures and promises
*

Discussions
* https://clojureverse.org/t/missionary-new-release-with-streaming-support-design-notes/4510/7
* https://clojureverse.org/t/cloroutine-v1/3300/8
* https://www.reddit.com/r/Clojure/comments/k2db8k/leonoelmissionary_a_functional_effect_and/

Design notes
* Like Haskell, effects are values
* Every forked process must eventually be joined
* Every asynchronous operation must be cancellable
* Leverages standard clojure primitives (collections, transducers, reducing functions, reference types, first-class `nil`) to prevent combinatorial explosion of operators
* Asynchronous design for efficiency and clojurescript compatibility
* Unified representation of signals and streams (multiple-value producers) supporting backpressure propagation of discrete events and lazy sampling of continuous values.
* Simple, REPL-friendly, dependency-free underlying protocols : [task](https://github.com/leonoel/task) & [flow](https://github.com/leonoel/flow), respectively single and multiple value producers.


# Projects using Missionary
* hyperfiddle/hfql, a streaming datomic pull evaluator
* hyperfiddle/photon, a reactive dialect of Clojure for distributed dataflow
* hyperfiddle/zero, full-stack web apps with incremental view maintenance all the way from database views to DOM views, as one streaming computation

# FAQ

*What is reactive programming?* – dataflow programs can more or less be visualized as flowchart DAGs with functions on the edges. The flowchart is reactive like a spreadsheet, so if you have a cell C1 = F(A1, B1), if B1 changes, C1 will recompute and flow downstream.

# Community

[#missionary](https://app.slack.com/client/T03RZGPFR/CL85MBPEF) on Clojurians slack

# References
* Nathaniel J Smith's posts on structured concurrency
* ZIO
* Jane Street Incremental
