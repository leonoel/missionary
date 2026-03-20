# Concurrency Test Framework

Custom concurrency test framework for missionary flows. Generates protocol-valid scenarios by construction via process ownership.

Replaces the Lincheck-based approach which was a poor fit for the flow protocol — Lincheck generates arbitrary interleavings without understanding protocol ordering (step → transfer → step → transfer), requiring yield-spin blocking, timeout skipping, and message-format coupling.

## Architecture

- **ProcessDebug protocol**: `valid-ops`, `terminal?`, `dbg-state`. Processes self-advertise weighted operations.
- **DummyFlow**: Clojure reify implementing ProcessDebug + IFn. Configurable `step-on-init`. Transfer values follow `0 1 1 2 3 3 4 5 5 ...` (odd numbers repeated, exercises equality-based work skipping). Supports `re-step` operation (consecutive transfer — fires step during deref).
- **Root**: split into root-transfer + root-cancel (two ProcessDebug reifys, shared state). Enables concurrent cancel + transfer.
- **Worker pool**: reusable across runs. T daemon threads, SynchronousQueue per thread for commands, shared LinkedBlockingQueue for results.
- **Arbiter**: single-threaded push coordinator. Dispatches ops to workers, collects results, records history.
- **Escalation runner**: ops-per-run from 2..max-ops, runs = budget/ops per level. Stops on first failure.

## Key Decisions

### Process ownership guarantees protocol adherence

Single-ownership of processes means the arbiter only dispatches one operation per process at a time. Cross-process effects can only EXPAND the valid operation set, never INVALIDATE a selected command.

Proof sketch — for each DummyFlow state bit, can it be set externally (by another thread's operation on a different process)?

- **STEPPED**: only set by `step()` (test operation, single-ownership prevents) and `invoke()` (subscribe, once at setup). Cannot be set externally.
- **DONE**: only set by `done()` (test operation, single-ownership prevents). Cannot be set externally.
- **CRASHED**: only set by `Iter.deref()` when `shouldThrow` is armed. The operator only derefs a DummyFlow when it is STEPPED. If the arbiter selected step/done (which require STEPPED=0), the operator has no reason to deref. Cannot be set externally at a time that matters.

Cross-process effects that DO occur:
- **STEPPED cleared**: operator derefs DummyFlow iterator → clears STEPPED. Enables more commands (step/done become valid). Expansion, not invalidation.
- **CANCELLED set**: operator cancels DummyFlow's iterator. Doesn't affect step/done/crash validity (CANCELLED is orthogonal to producer-side operations).

For root-transfer: STEPPED can only leave via transfer (CAS STEPPED→CLAIMED, single-ownership) or done-fn (which doesn't change STEPPED — unconditional DONE). Cannot lose STEPPED externally in a way that makes transfer invalid.

### Root state machine

States: TRANSFERRED(0), STEPPED(1), CLAIMED(2), SDT(3), DONE(4).

- **step-fn**: idempotent — STEPPED→STEPPED, SDT→SDT accepted silently. The enforcer wrapping the output flow catches double-step; the root does not duplicate this check.
- **done-fn**: unconditional → DONE from any state. The enforcer catches done-after-step-without-transfer; the root does not duplicate.
- **transfer-fn**: CAS STEPPED→CLAIMED, deref iterator, update-state (CLAIMED→TRANSFERRED, SDT→STEPPED, DONE→DONE). On exception: update-state then rethrow.
- **valid-ops (transfer)**: offered only when `state == STEPPED`. No `terminated` flag — the state machine captures termination via DONE(4).

### Barrier synchronization: AtomicInteger spin-wait

Replaced CyclicBarrier to minimize cross-thread memory fences. Workers decrement an AtomicInteger and spin until it reaches 0, with `Thread.yield()` every 1M iterations (matching Lincheck's Spinner pattern). No locks, no parking.

Fresh AtomicInteger allocated per barrier round to avoid races from counter reuse across rounds.

`shutting-down` volatile in the worker pool — workers check it during spin to exit if the arbiter is shutting down.

### Barrier frequency: init/gap formula

Computed per escalation level from ops-per-run:

```clojure
barrier-init = min(10, max(0, (quot ops 3) - 1))
barrier-gap  = max(2, floor(ln(ops) * 2))
```

| ops | init | gap |
|-----|------|-----|
| 2   | 0    | 2   |
| 3   | 0    | 2   |
| 5   | 0    | 3   |
| 10  | 2    | 4   |
| 50  | 10   | 7   |

At ops=2: init=0, barrier on first dispatch — maximum overlap for concurrent child steps.

### Enforcer2 integration

Tests use `flow-protocol-enforcer2` (callback-based, doesn't violate protocol itself). `on-violation` callback writes to a shared `violations` atom. The arbiter checks it after each operation and after cleanup.

`{:ready-on-init false}` opts for discrete flows (eduction with filter/take) where the initial value may be filtered out.

### Worker pool reuse

Pool created once per `run-conc-test` call, reused across all escalation levels and runs. `shutting-down` volatile reset at the start of each `run-arbiter` call. Stale result-queue messages drained at run start.

### RNG seed capture

Per-run seed created from `(.nextLong (java.util.Random.))`. Returned in `run-arbiter` result as `:seed`. Printed on failure. Accepted as `:seed` in config for replay.

### Cleanup

1. Set `shutting-down`, drain in-flight ops (100ms poll timeout)
2. Cancel root
3. Drain loop: transfer if root STEPPED, done each non-terminal DummyFlow. Transfer catches `ExceptionInfo("intended crash")` and `missionary.Cancelled` — all other exceptions propagate.
4. If root not terminal: check violations first (report violation), then check timeout (report with dbg-state).
5. Top-level `(catch Throwable e)` in `run-arbiter` catches anything that escapes — stores as failure with violation priority.

### Named processes carry `:role`

Each process in the setup result has `{:name string :role keyword :process ProcessDebug}`. Roles: `:dummy`, `:root-transfer`, `:root-cancel`. Used by cleanup and `dummy-processes` filter.

## Files

- `test/missionary/conc.clj` — framework: ProcessDebug, DummyFlow, Root, Worker Pool, Arbiter, Runner
- `test/missionary/conc_flow_test.clj` — 32 operator/signal/stream/chain tests + buggy-flow-2 validation test
- `src/missionary/flow_protocol_enforcer2.cljc` — modified: `ready-on-init?` boolean → opts map with `:ready-on-init` key

## Tests

### Operator tests (20)
latest (0-4), zip (1-4), sample (2-4), reductions, eduction, relieve, buffer, eduction-filter, eduction-mapcat, eduction-take, buffer-small

### Signal topologies (7)
basic, diamond, triple, self-sample, nested, mixed, semigroup

### Stream topologies (2)
basic, diamond

### Chain topologies (3)
reductions-relieve, filter-reductions, latest-reductions

### Validation test
`buggy-flow-2`: synthetic 2-input operator with non-atomic check-then-act race on a plain `object-array` flag. Eagerly derefs children on step. Correct single-threaded, double-steps under concurrency. Uses `step-on-init=false` so both DummyFlows have step available immediately. Framework detects "double step" violation without any artificial race widening (no Thread/yield).

## Rejected Approaches

- **Lincheck with blocking + timeout skipping**: yield-spin blocking masks concurrency bugs. Timeout-skip loop has message-format coupling.
- **Lincheck with void returns**: discards linearizability checking.
- **CyclicBarrier for thread sync**: ReentrantLock-based full memory barrier right before op execution masks missing-fence bugs — replaced with AtomicInteger spin-wait.
- **Single AtomicInteger reused across barrier rounds**: race where a still-spinning worker from the previous round gets a counter overwrite — fresh AtomicInteger per round.
- **Property-based pre-generation (test.check)**: pre-generated scenarios can't adapt to operator's runtime behavior.
- **Decentralized threads (no arbiter)**: history ordering loss, local weight selection suboptimal.
- **Root assertions duplicating enforcer checks**: step-fn/done-fn throwing on invalid states causes secondary "step/done cannot throw" violations from enforcer — root is idempotent, enforcer is authoritative.
- **`terminated` volatile in root**: parallel truth source diverging from state machine — state DONE(4) is sufficient.

## Future Work

- **Linearizability checking**: result infrastructure `[:ok v t]` / `[:ex e t]` is in place.
- **Shrinking**: operation history enables replay + minimize.
- **Prescribed sequence fragments**: inject specific operation sequences alongside random selection.
- **Model checking mode**: systematic interleaving exploration (vs current random concurrent execution).
- **SynchronousQueue replacement**: volatile fields for command dispatch would reduce arbiter↔worker fencing. Not blocking — queues only fence arbiter↔worker, not worker↔worker.
