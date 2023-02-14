# Missionary – a functional effect and streaming system for Clojure/Script

Missionary is a reactive dataflow programming toolkit providing referentially transparent operators for lazy continuous signals, eager discrete streams, and IO actions. Missionary aims to improve over state-of-the-art reactive systems, it can be used as a general-purpose asynchronous programming toolkit but also as a basis for event streaming and incremental computations.

```clojure 
(require '[missionary.core :as m])

; this is a reactive computation, the println reacts to input changes
(def !input (atom 1))
(def main (m/reactor
            (let [>x (m/signal! (m/watch !input))       ; continuous signal reflecting atom state
                  >y (m/signal! (m/latest + >x >x))]    ; derived computation, diamond shape
              (m/stream! (m/ap (println (m/?< >y))))))) ; discrete effect performed on successive values

(def dispose! (main #(prn ::success %) #(prn ::crash %)))
; 2
(swap! !input inc)
; 4
(dispose!)

; Each change on the input propagates atomically through the graph. 3 is an inconsistent state and is therefore not computed.
```

Features
* Discrete event streams with backpressure
* Continuous-time signals with lazy sampling
* Correct incremental maintenance of dynamic DAGs without inconsistent states (aka FRP glitches)
* Correct error handling by default. Strict process supervision provides transparent propagation of cancellation and failure, with strong resource cleanup guarantees
* Asynchronous design for efficiency and ClojureScript compatibility
* Reactive Streams compliant

Key ideas
* Unification of functional effect systems and FRP / dataflow programming
* Unification of continuous-time and discrete-time primitives under the common Flow protocol
* Embrace and reuse Clojure core abstractions including collections, transducers, reducing functions and reference types
* Sequential composition DSL is a superset of clojure with full metaprogramming support, same expressive power as monads

Missionary's mission is to establish a rigorous foundation for modern web programming, particularly sophisticated real-time collaborative applications. Missionary's reactive primitives are fully separated and unbundled in order to achieve low-level control over every aspect of the computation (discrete vs continuous, eager vs lazy, allocation and reaction boundaries).

Missionary can be used as a foundation to build higher level reactive abstractions. For example, Missionary's "reactive VM" is the compiler target of [hyperfiddle/photon](https://hyperfiddle.notion.site/Reactive-Clojure-You-don-t-need-a-web-framework-you-need-a-web-language-44b5bfa526be4af282863f34fa1cfffc), a reactive dialect of Clojure/Script for UI programming.

# Dependency

Project maturity: experimental, but stable. The current development priority is documentation.

```clojure
{:deps {missionary/missionary {:mvn/version "b.27"}}} 
```
[![clojars](https://img.shields.io/clojars/v/missionary.svg)](https://clojars.org/missionary)
[![cljdoc](https://cljdoc.org/badge/missionary/missionary)](https://cljdoc.org/d/missionary/missionary/CURRENT)
[![build](https://api.travis-ci.com/leonoel/missionary.svg?branch=master)](https://app.travis-ci.com/github/leonoel/missionary)
[![license](https://img.shields.io/github/license/leonoel/missionary.svg)](LICENSE)

# Prior art

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

Discussions:
* [ClojureVerse (2019)](https://clojureverse.org/t/missionary-new-release-with-streaming-support-design-notes/4510)
* [reddit (2021)](https://www.reddit.com/r/Clojure/comments/k2db8k/leonoelmissionary_a_functional_effect_and/)

Tutorials
1. [Hello task](doc/tutorials/hello_task.md)
2. [Hello flow](doc/tutorials/hello_flow.md)
3. [Comparison to RxJava](doc/tutorials/rx_comparison.md)

How-to guides: [cookbook](https://github.com/leonoel/missionary/wiki)

Explanations
* [re:Clojure 2021](https://www.youtube.com/watch?v=tV-DoiGdUIo)

## Community

[#missionary](https://app.slack.com/client/T03RZGPFR/CL85MBPEF) on Clojurians slack

