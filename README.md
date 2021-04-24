# Missionary

A functional effect and streaming system for clojure and clojurescript.

[![clojars](https://img.shields.io/clojars/v/missionary.svg)](https://clojars.org/missionary)

[![cljdoc](https://cljdoc.org/badge/missionary/missionary)](https://cljdoc.org/d/missionary/missionary/CURRENT)

[![build](https://travis-ci.org/leonoel/missionary.svg?branch=master)](https://travis-ci.org/leonoel/missionary)

[![license](https://img.shields.io/github/license/leonoel/missionary.svg)](LICENSE)

## Maturity

Experimental status, breaking changes should be expected.


## Rationale

Imperative-style concurrent programming comes along with serious pitfalls. Uncontrolled process spawning requires discipline and ceremony to properly handle failure and cancellation propagation, mixing essential and accidental complexity. Popular techniques such as channels and futures/promises don't solve this problem and often introduce concurrency concerns in otherwise purely sequential logic. `missionary` takes a functional approach to this problem, modelling processes as values with precise semantics.


## Guidelines

* Simple, REPL-friendly, dependency-free underlying protocols : [task](https://github.com/leonoel/task) & [flow](https://github.com/leonoel/flow), respectively single and multiple value producers.
* Strict supervision providing transparent propagation of cancellation and failure, with strong resource cleanup guarantees.
* Unified representation of multiple-value producers supporting backpressure propagation of discrete events and lazy sampling of continuous values.
* [Reactive Streams](http://www.reactive-streams.org/) compliant
* Asynchronous design for efficiency and clojurescript compatibility
* Lightweight API leveraging standard clojure primitives (collections, transducers, reducing functions, reference types, first-class `nil`) to prevent combinatorial explosion of operators
* Expressive syntax


## Documentation

### Reference
[`missionary.core`](https://cljdoc.org/d/missionary/missionary/CURRENT/api/missionary.core)

### Tutorials
1. [Hello task](doc/tutorials/hello_task.md)
2. [Hello flow](doc/tutorials/hello_flow.md)
3. [Comparison to RxJava](doc/tutorials/rx_comparison.md)

### Guides
1. [Retry with backoff](doc/guides/retry_backoff.md)
2. [Happy eyeballs](doc/guides/happy_eyeballs.md)
3. [Iterative queries](doc/guides/iterative_queries.md)


## Community

* clojurians slack : [#missionary](https://app.slack.com/client/T03RZGPFR/CL85MBPEF)
