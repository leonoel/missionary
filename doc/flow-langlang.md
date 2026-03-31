# Flow Protocol — Implementation Guide

> For implementing missionary flows from scratch. Audience: LLM.
> Source: github.com/leonoel/flow spec + author corrections.

## Preamble

### Roles

- **Producer**: provides flow function, returns iterator, calls step and done.
- **Consumer**: provides step callback (notifier) and done callback (terminator), calls transfer (deref) and cancel.

### Vocabulary

| Spec term       | Missionary term | Who calls | Meaning                              |
|-----------------|-----------------|-----------|--------------------------------------|
| notifier()      | step            | producer  | "I have a value ready"               |
| terminator()    | done            | producer  | "No more values, resources released" |
| deref(iterator) | transfer        | consumer  | "Give me the value"                  |
| iterator()      | cancel          | consumer  | "Please stop"                        |

### Universal Constraints

- Every operation (construction, step, done, transfer, cancel) MUST NOT block.
- Step, done, cancel return values MUST be ignored.
- Nil is a valid transfer value, not a sentinel.

---

## P1: Handshake

The alternating step/transfer protocol. Producer steps, consumer transfers, repeat. Backpressure is structural — one step, one transfer.

### IS NOT

- **NOT Reactive Streams `onNext`** — Flow is pull (consumer deref's), not push (producer pushes).
- **NOT a Go channel** — Flow is point-to-point, unbuffered, strictly alternating. No buffer, no multi-producer.
- **NOT `request(n)` backpressure** — Backpressure is implicit (one step, one transfer), not explicit demand counting.

### Knowledge

- **K1.1**: Step and transfer strictly alternate, starting with step. No double-step. No unsignaled transfer.
- **K1.2**: Transfer unlocks the producer — it may step again. The unlock happens DURING deref execution, not after return.
- **K1.3**: Producer MAY step inside transfer (during deref), but ONLY on the non-throwing path. Stepping then throwing creates a ghost step the consumer can never honor.
- **K1.4**: Throwing transfer kills the step channel permanently. Process transitions to cleanup (→P3).

---

## P2: Cancellation

Consumer-initiated request to stop. Orthogonal to the handshake — cancel doesn't change which handshake state you're in.

### IS NOT

- **NOT `Thread.interrupt()`** — Cancel must not throw, must be idempotent.
- **NOT Reactive Streams cancel** — Flow allows unbounded post-cancel transfers (graceful shutdown).
- **NOT immediate** — Cancel is a request with no timing guarantee. The producer decides when and how to wind down.
- **NOT a handshake state** — Cancel is orthogonal; the step/transfer alternation continues through and after cancel.

### Knowledge

- **K2.1**: Cancel is idempotent. Multiple calls have no additional effect.
- **K2.2**: Cancel must not block, must not throw.
- **K2.3**: Cancel can happen at any time, including after done. Producer must handle gracefully.
- **K2.4**: Post-cancel, the producer MAY continue stepping/transferring. No upper bound. Typically 0–1, often a `missionary.Cancelled` throw on transfer. Implementation must handle any count.

---

## P3: Lifecycle

Every process instance reaches done exactly once. Three paths converge.

### IS NOT

- **NOT try/finally** — Done can be async. Cleanup may involve awaiting child processes before the producer can call done.
- **NOT Erlang supervision** — No restart, no escalation. Only the parent-waits-for-child cleanup ordering pattern.
- **NOT optional** — Done is unconditional on every path, exactly once.

### Knowledge

- **K3.1**: Done is called exactly once by the producer. Means: no more values AND all resources released.
- **K3.2**: Done must not block, must not throw. Return value ignored.
- **K3.3**: Producer must not step after done.
- **K3.4**: Three terminal paths, all ending in done:
  - **Exhaustion**: no more values → done.
  - **Error**: throwing transfer → step channel dead → cleanup → done.
  - **Cancel**: consumer requests stop → producer cleans up → done.
- **K3.5**: Composite flows (flow spawning child processes): cancel child → await child done → done self. Resource cleanup is ordered bottom-up through the process tree.

---

## P4: Concurrency

In CLJ, P1–P3 invariants must hold when step/cancel/transfer/done arrive from different threads. Not a protocol concern — an implementation concern.

### IS NOT

- **NOT solvable with locks alone** — Deadlocks: confirmed failure mode of prior missionary iterations.
- **NOT per-operation thread safety** — Invariants are cross-operation (no double-step, exactly-once done).
- **NOT relevant in CLJS** — Single-threaded event loop; P4 is vacuously satisfied.

### Knowledge

- **K4.1**: Step can arrive from any thread.
- **K4.2**: Cancel and transfer may arrive concurrently. This can constitute a protocol violation; not currently enforced (per author).
- **K4.3**: Exactly-once done must hold when multiple terminal paths race (e.g., cancel arrives while error path in progress).
- **K4.4**: No-double-step must hold when multiple ready signals arrive concurrently.

---

## Couplings

| Coupling                  | Direction     | What happens                                                                     |
|---------------------------|---------------|----------------------------------------------------------------------------------|
| Error bridge              | P1 → P3       | Throwing transfer kills handshake, triggers lifecycle cleanup                    |
| Cancel eventuality        | P2 → P3       | Cancel eventually causes done; timing and intervening transfer count unspecified |
| Done closes handshake     | P3 → P1       | After done, no more steps allowed                                                |
| Concurrency threatens all | P4 → P1,P2,P3 | All invariants must hold under concurrent access in CLJ                          |

---

## Examples

### E1: Single-value flow (P1.K1.1, P3.K3.4 exhaustion)

```
consumer: flow(n, t) → it
producer: n()                          -- step: value ready
consumer: @it → v                     -- transfer: got value
producer: t()                          -- done: exhausted
```

### E2: Infinite flow, cancelled (P1.K1.1, P2.K2.4, P3.K3.4 cancel)

```
consumer: flow(n, t) → it
producer: n()                          -- step
consumer: @it → v1                    -- transfer
producer: n()                          -- step
consumer: @it → v2                    -- transfer
consumer: it()                         -- cancel
producer: n()                          -- post-cancel step (legal per K2.4)
consumer: @it → throws Cancelled      -- post-cancel transfer throws
producer: t()                          -- done
```

### E3: Error flow (P1.K1.4, P3.K3.4 error)

```
consumer: flow(n, t) → it
producer: n()                          -- step
consumer: @it → throws Error          -- transfer fails
                                       -- step channel dead (K1.4), no more steps
producer: t()                          -- done: cleanup complete
```

### E4: Step inside transfer (P1.K1.2, P1.K1.3)

```
consumer: flow(n, t) → it
producer: n()                          -- step
consumer: @it →                       -- transfer begins (deref executing)
  producer: n()                        -- step inside transfer (legal: will not throw)
  → v1                                -- transfer returns
consumer: @it → v2                    -- next transfer (step already happened)
```

### E5: Composite flow cancelled (P3.K3.5, P2→P3 coupling)

```
consumer: flow(n, t) → it             -- parent spawns child internally
consumer: it()                         -- cancel parent
parent:  child-it()                    -- parent cancels child
child:   child-t()                     -- child done (resources released)
parent:  t()                           -- parent done (only after child)
```

### E6: Post-done cancel (P2.K2.3)

```
producer: t()                          -- done
consumer: it()                         -- cancel: no-op, already terminated
```

---

## Verification Battery

Give a fresh session ONLY this document, then ask:

1. Implement a single-value flow → correct step/transfer/done sequence?
2. Implement an infinite counting flow → handles cancel, calls done, no double-step?
3. "What happens if deref throws?" → no more steps, cleanup, done still called?
4. "Can the producer step inside deref?" → yes, only on non-throwing path?
5. "What if cancel arrives after done?" → no-op, handled gracefully?
6. Implement a map-flow (child flow + fn) → supervision ordering correct?
