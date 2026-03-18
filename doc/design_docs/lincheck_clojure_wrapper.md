# Lincheck Clojure Wrapper

REPL-driven Lincheck stress testing for missionary flow operators, without Java compilation.

## Problem

The Java-based Lincheck test harness (`lincheck/missionary/FlowLincheckTest.java`) requires `bb lincheck:compile` before each run. Iteration speed is bottlenecked by the compile-run-fix cycle.

## Solution

`test/missionary/lincheck.clj` — generates `@Operation`-annotated JVM classes at runtime using `clojure.asm`, enabling Lincheck stress tests directly from the REPL.

## Architecture

```
DummyFlow(s) --> enforcer --> [Operator Under Test] --> enforcer --> Root Consumer
  (Java)        (Clojure)         (missionary.core)     (Clojure)    (IDeref+IFn reify)
```

### Key Components

**Root consumer** (`->root`): Subscribes to a flow and manages the consumer state machine via `AtomicInteger`. States: `TRANSFERRED -> STEPPED -> CLAIMED -> TRANSFERRED` (with `STEPPED_DURING_TRANSFER` and `DONE` transitions). Returned as an `IDeref` (deref = transfer) + `IFn` (invoke = cancel) reify. Uses `clojure.lang` interfaces instead of a custom `definterface` — ASM-generated classes are loaded by the system class loader, which can't resolve classes on Clojure's `DynamicClassLoader`.

**Class generator** (`gen-lincheck-class`): Emits JVM bytecode for a Lincheck-compatible test class with:
- Zero-arg constructor that calls a static `setupFn` (Clojure fn) and unpacks the result vector into `root` and `dummies` fields
- `@Operation` methods per DummyFlow: `step{i}`, `done{i}`, `crash{i}` (group `"f{i}"`, serialized per-flow)
- `@Operation transfer` (group `"consumer"`) delegating to `IDeref.deref()`
- `@Operation cancel` (no group — concurrent with everything) delegating to `IFn.invoke()`
- `@Validate validate` — checks per-instance `violationSeen` flag, throws `AssertionError` if set

Every `@Operation` method is wrapped in a try/catch for `missionary.ProtocolViolation`. The catch sets `violationSeen = true` on the instance and rethrows. Lincheck captures the exception as an `ExceptionResult` (does not abort the scenario), then calls `validate()` after all operations complete. This detects protocol violations even when Lincheck considers the execution linearizable.

`enforcer/violated` throws `ProtocolViolation` (a `RuntimeException` subclass in `java/missionary/`) on CLJ, `ex-info` on CLJS. No per-test plumbing needed — the default `enforcer/flow` uses it automatically.

Class bytes are written to `lincheck-classes/` (on the classpath) so Lincheck worker threads can load them. Class names include a monotonic counter suffix so REPL reloads generate fresh classes (the JVM caches `Class/forName` results).

**Agent bypass** (`ensure-agent-bypass!`): Eliminates ~7 minute agent overhead in REPL. See [Agent Bypass](#agent-bypass) below.

### Why ASM, Not deftype

Lincheck requires test classes with both a zero-arg constructor (for per-scenario instantiation) and mutable per-instance state. `deftype` can provide one but not both — zero-arg constructor requires no fields, but mutable state requires fields (which adds constructor args). The `clojure.asm` class generator resolves this by emitting a zero-arg constructor that delegates to a static setup function.

## Usage

```clojure
(require '[missionary.core :as m])
(require '[missionary.lincheck :as lc])
(require '[missionary.flow-protocol-enforcer :as enforcer])

(lc/def-lincheck-flow-test latest-1 1
  (let [d (lc/->dummy-flow)]
    [(lc/->root (enforcer/flow "out" (m/latest identity (enforcer/flow "in-0" d))))
     d]))

(lc/run-lincheck-stress-test latest-1 {:iterations 100})
```

`def-lincheck-flow-test` arguments:
- `name` — var name for the generated class
- `arity` — number of DummyFlow inputs (literal integer)
- `body` — returns `[root d0 d1 ...]` vector; protocol violations detected automatically via `@Validate`

`run-lincheck-stress-test` options: `:iterations`, `:threads`, `:actors-per-thread`, `:invocations-per-iteration`.

## Agent Bypass

### Problem

Lincheck's `withLincheckJavaAgent` calls `install()` / `uninstall()` around every `check()`. In a REPL with thousands of loaded classes, this takes ~7 minutes — it retransforms every class looking for Kotlin coroutine suspension points. For non-Kotlin code in STRESS mode, this transformation is a complete no-op.

### Mechanism

`ensure-agent-bypass!` (called automatically by `run-lincheck-stress-test`):

1. `ByteBuddyAgent/install` — obtains `Instrumentation` instance (~0ms after first attach)
2. Extracts `bootstrap.jar` from `lincheck-jvm-agent-3.4.jar` — contains `Injections` class (required by `withLincheckTestContext`)
3. `instrumentation.appendToBootstrapClassLoaderSearch(bootstrapJar)` — makes `Injections` loadable
4. `LincheckJavaAgentKt/setTraceJavaAgentAttached(true)` — `withLincheckJavaAgent` skips install/uninstall, calls test block directly

### Why This Is Safe

- In STRESS mode, no test thread registers an `EventTracker` (only `ManagedStrategy` does)
- All `Injections.*` entry points bail out on null descriptor checks — dead code
- The transformer scans for Kotlin coroutine suspension, finds none in Java/Clojure, injects nothing
- `lateinit` fields (`instrumentation`, `instrumentationMode`, `instrumentationStrategy`) are initialized via reflection to prevent crashes in `ensureObjectIsTransformed`; `Field.get()` bypasses the Kotlin getter (returns null, doesn't throw)
- Result: **~7 min -> ~1s** per stress test

### Scope Limitation

This bypass is for STRESS mode only. Model checking requires actual agent instrumentation and is out of scope.

## Concurrency Model

| Operation                        | Group      | Constraint                 |
|----------------------------------|------------|----------------------------|
| `step{i}`, `done{i}`, `crash{i}` | `"f{i}"`   | Serialized per-flow        |
| `transfer`                       | `"consumer"` | Single consumer          |
| `cancel`                         | (none)     | Concurrent with everything |

Step/done on different flows CAN run concurrently. Step/done CAN run concurrently with transfer.

## DummyFlow

Java implementation (`lincheck/missionary/DummyFlow.java`) used in tests for performance (~1s vs ~3s per test). Controllable flow with `AtomicInteger` state flags (`STEPPED | DONE | CANCELLED | CRASHED`). External control via `step()`, `done()`, `setThrow()`. Implements the flow protocol: `invoke(step, done) -> iterator`.

A pure Clojure port exists as `->clj-dummy-flow` in `lincheck.clj` (with `IDummyFlow` interface), kept for reference but not used in tests. Switching requires changing `dummy-type` to `IDummyFlow` and `invokeVirtual` to `invokeInterface` in the ASM generator.

## Test Suite

`test/missionary/lincheck_flow_test.clj` — ports all 16 Java tests from `FlowLincheckTest.java`.

```clojure
(require '[missionary.lincheck-flow-test :as ft])
(ft/run-all)                          ;; all 16 tests, ~10s
(ft/run-all {:iterations 200})        ;; custom params
(lc/run-lincheck-stress-test ft/latest-2 {:iterations 100})  ;; single test
```

Coverage: `latest` (0-4 flows), `zip` (1-4), `sample` (2-4), `reductions`, `eduction`, `relieve`, `buffer`.
