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

* Simple, REPL-friendly, dependency-free underlying protocols : task ([specification](https://github.com/leonoel/task)) & flow (specification coming soon), respectively single and multiple value producers.
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
1. [Hello task](https://cljdoc.org/d/missionary/missionary/CURRENT/doc/readme/tutorials/hello-task)
2. [Hello flow](https://cljdoc.org/d/missionary/missionary/CURRENT/doc/readme/tutorials/hello-flow)

### Guides
1. [Retry with backoff](https://cljdoc.org/d/missionary/missionary/CURRENT/doc/readme/guides/retry-with-backoff)
2. [Happy eyeballs](https://cljdoc.org/d/missionary/missionary/CURRENT/doc/readme/guides/happy-eyeballs)
