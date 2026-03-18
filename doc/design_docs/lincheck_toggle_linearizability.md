# CAS Toggle Linearizability in Lincheck Tests

## Symptom

`latest/4` fails Lincheck stress tests intermittently. Both Java and Clojure versions affected.

```
| Thread 1              | Thread 2  |
|-----------------------|-----------|
| transfer(): [1 1 1 1] |           |
|-----------------------|-----------|
| step2():              | done0():  |
| transfer(): skip      |           |
```

No sequential ordering of `{step2, done0, transfer}` produces `transfer()="skip"` — all three orderings yield `transfer()=[1,1,0,1]`.

## Root Cause

The CAS toggle in `Latest.step(ps, i)` has two paths:

```java
if (s == IDLE) {
    // WIN: CAS IDLE→null, event(), ready() → rootStep
} else {
    // ENQUEUE: link into sync chain, return immediately
}
```

**Win**: `step` processes the event synchronously — `ready()` calls `rootStep`, root goes `STEPPED`.

**Enqueue**: `step` returns *before* `rootStep` is called. The thread holding the toggle eventually drains the queue and calls `rootStep`, but the caller has already moved on.

### The Breaking Interleaving

```
T2 (done0):  Latest.step(ps, 0) — wins toggle (CAS IDLE→null)
T1 (step2):  Latest.step(ps, 2) — sync≠IDLE → enqueues, returns
T1 (transfer): rootState==TRANSFERRED → "skip"
T2:          drains sync chain → event(2) → ready() → rootStep  [too late]
```

`step2` returns on T1, `transfer` runs next on T1. But `rootStep` hasn't fired — it fires later on T2. Root is still `TRANSFERRED`, so transfer returns `"skip"`.

### Why Sequential Spec Differs

In sequential execution, there's no toggle contention. `step2` always wins the toggle (sync=IDLE), calls `rootStep` synchronously. Transfer always sees `STEPPED`.

This asymmetry — synchronous in sequential, asynchronous under contention — is the linearizability violation.

## Not an Operator Bug

The CAS toggle's fire-and-forget semantics are correct for the flow protocol. `step` is a notification, not a synchronous request-response. The consumer will eventually see it. The issue is that Lincheck's linearizability model assumes operations take effect between invocation and response — but enqueued notifications take effect *after* `step` returns (on a different thread).

## Status

The test harness retains per-flow groups (`"f0"`, `"f1"`, ...) and `"consumer"` for transfer. The concurrent producer-producer and producer-consumer scenarios are the purpose of the stress test. The failures this analysis describes are real observations that the test is meant to surface.
