# Missionary – an asynchronous programming toolkit for clojure and clojurescript

## Goals
* Provide solid foundations to meet the needs of modern web programming, especially sophisticated real-time collaborative applications.
* Promote functional effects as the default Clojure concurrency model. Underlying abstractions, [task](https://github.com/leonoel/task)s and [flow](https://github.com/leonoel/flow)s, are dependency-free for this reason, `missionary` is merely a reference implementation.
* Unification of functional effect/streaming systems and reactive programming in a highly composable model leveraging referential transparency when it matters.

## Principles
* Embrace the host, like clojure. Don't try to fix null pointers, exception handling, thread interruption, or lack of TCO.
* Embrace the language. Support the existing ecosystem, leverage metaprogramming and reuse existing abstractions : collections, transducers, reducing functions, reference types.

## Features
* strict supervision by default
* expressive IOC syntax with backtracking
* standard effect system and concurrency toolkit
* backpressured streaming of discrete events ([Reactive Streams](http://www.reactive-streams.org/) adapters provided)
* lazy sampling of continuous time variables
* reactive programming facilities

## Usage

Project status: experimental

```clojure
{:deps {missionary/missionary {:mvn/version "b.22"}}} 
```
[![clojars](https://img.shields.io/clojars/v/missionary.svg)](https://clojars.org/missionary)
[![cljdoc](https://cljdoc.org/badge/missionary/missionary)](https://cljdoc.org/d/missionary/missionary/CURRENT)
[![build](https://api.travis-ci.com/leonoel/missionary.svg?branch=master)](https://app.travis-ci.com/github/leonoel/missionary)
[![license](https://img.shields.io/github/license/leonoel/missionary.svg)](LICENSE)

## Prior art

### vs imperative
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

### vs functional
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

### vs reactive
While traditional streaming engines are focusing on discrete events, `missionary` is designed upfront to also support
continuous time, which is the realm of [FRP](https://en.wikipedia.org/wiki/Functional_reactive_programming). This is
made possible by the flow abstraction, a foundational protocol allowing a producer to signal availability of a value
without eagerly computing it, making suitable for both backpressured event streaming and lazy sampling of time-varying
values. This unified representation bridges the gap between functional and reactive programming into a consistent model
providing the best of both worlds.

## Documentation

API Reference: [`missionary.core`](https://cljdoc.org/d/missionary/missionary/CURRENT/api/missionary.core)

How-to guides: [cookbook](https://github.com/leonoel/missionary/wiki)

### Tutorials
1. [Hello task](doc/tutorials/hello_task.md)
2. [Hello flow](doc/tutorials/hello_flow.md)
3. [Comparison to RxJava](doc/tutorials/rx_comparison.md)

## Ecosystem

* [Hyperfiddle](https://hyperfiddle.net) leverages `missionary` to build its [next-generation web stack](https://hyperfiddle.notion.site/Reactive-Clojure-You-don-t-need-a-web-framework-you-need-a-web-language-44b5bfa526be4af282863f34fa1cfffc).

## Community

Intermediate reports and discussions : [clojureverse](https://clojureverse.org/t/missionary-new-release-with-streaming-support-design-notes/4510/7), [reddit](https://www.reddit.com/r/Clojure/comments/k2db8k/leonoelmissionary_a_functional_effect_and/)

Live chat : [#missionary](https://app.slack.com/client/T03RZGPFR/CL85MBPEF) on Clojurians slack
