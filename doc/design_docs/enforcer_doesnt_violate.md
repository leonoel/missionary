# Frame

## Problem

The flow protocol enforcer (`src/missionary/flow_protocol_enforcer.cljc`) violates the flow protocol itself when detecting violations. It throws exceptions inside `step` (notifier) and `done` (terminator) callbacks, which the protocol requires must not throw. Only `transfer` (deref) is allowed to throw.

Throw sites that violate the protocol:

**Inside `step` (notifier — must not throw):**
- `step after done`
- `step after crash`
- `double step`

**Inside `done` (terminator — must not throw):**
- `done after step without transfer`
- `done called twice`

**Inside `step` via `cannot-throw`:**
- If the real (wrapped) notifier throws, `cannot-throw` re-throws as ProtocolViolation — the enforcer's step still throws.

**Inside `done` via `cannot-throw`:**
- Same: if the real terminator throws, the enforcer's done throws.

Transfer-context throws are fine (transfer is allowed to throw). Constructor-context throws are fine.

## Motivation

An enforcer that violates the protocol causes cascading failures that mask the original violation. Operators under test may behave unexpectedly when their notifier/terminator throws, making the real bug harder to diagnose. The enforcer must be a correct protocol participant at all times.

## Non-goals

- Changing the flow protocol.
- Changing operator code.
- Adding new detection capabilities beyond what the enforcer already checks.
- Changing the happy path — pass-through behavior stays as-is.

## Constraints

- **Pure pass-through**: the enforcer must not change observable behavior. After detecting a violation, the call is forwarded to the consumer (or re-thrown for consumer callback violations) exactly as it would be without the enforcer.
- **Violations reported via callback**: a callback provided at construction time. Callback provider's responsibility not to throw synchronously.
- **No protocol violations by the enforcer**: the enforcer must not introduce violations that wouldn't exist without it. (Consumer callback re-throws are not introduced — they're preserved.)

## Resolved Questions

1. **`cannot-throw` return convention**: either-type. `cannot-throw` returns `[:ok nil]` or `[:ex e]` instead of throwing; call sites handle the either.
2. ~~Drain~~: not needed. Enforcer is pure pass-through — no intervention after violation.
3. ~~Done ordering~~: not needed. Enforcer forwards done as-is.
4. ~~Surfaceable vs unsurfaceable~~: false distinction. All violations use callback + pass-through.
5. **First-violation-wins**: not enforced by the enforcer. The callback or its caller can implement this if desired.

## Open Questions

None.

# Research

## Thread 1: Binding structure and name resolution

**Source**: `flow_protocol_enforcer.cljc:23-46`, Clojure `let` sequential binding semantics.

```
(fn [step done]                          ; outer — consumer's callbacks
  (let [step (fn [] ... step ...)        ; inner step SHADOWS outer. `step` in body = outer step (consumer's notifier)
        done (fn [] ... done ...)        ; inner done SHADOWS outer. `done` in body = outer done (consumer's terminator)
                                         ; BUT: `step` in done's body = INNER step (already bound)
        cancel (input-flow step done)]   ; passes INNER step/done to child. cancel = child's iterator.
    ...))
```

Key resolution:
- `(cannot-throw violated nm "step" step)` inside inner step → calls **consumer's notifier** (outer step). **Confirmed.**
- `(cannot-throw violated nm "done" done)` inside inner done → calls **consumer's terminator** (outer done). **Confirmed.**
- `(input-flow step done)` → passes **inner wrappers** to child. Child calls inner step/done. **Confirmed.**
- `@cancel` = `(deref child-iterator)` = transfer from child. `(cancel)` = cancel child. **Confirmed.** (#ground — `cancel` is actually the child's iterator; the name is misleading.)

## Thread 2: State machine — current states and transitions

**Source**: `flow_protocol_enforcer.cljc:24`, atom initial values and swap operations.

`!should-step?` encodes where we are in the step/transfer alternation:

| State | Meaning | Valid next operation |
|-------|---------|---------------------|
| `::init` | Before first step | step |
| `false` | Stepped, waiting for transfer | transfer |
| `true` | Transferred, waiting for step | step |

Transitions:
- **step**: `(swap-vals! !should-step? not)` → old value checked. Truthy (::init, true) = valid. Falsy (false) = double step.
- **transfer**: `(swap-vals! !should-step? not)` → old value checked. Falsy (false) = valid. Truthy (true, ::init) = invalid.

Note: `(not ::init)` = `false`, so `::init` → `false` on first step. Then alternates `false` ↔ `true`.

Other atoms: `!done?` (false → true, once), `!crashed?` (nil → exception, once), `!v` (last transferred value).

### Finding: swap-before-check corrupts state on violation {#swap-corrupt}

**Confidence: confirmed.** Both step and transfer use `swap-vals!` which atomically mutates state THEN returns old+new. When a violation is detected, the swap has already occurred:

- **Double step**: `!should-step?` was `false`, swapped to `true`. Violation detected (old=false). But state is now `true` (looks like "waiting for step" — wrong).
- **Double transfer**: `!should-step?` was `true`, swapped to `false`. State is now `false` (looks like "waiting for transfer" — wrong).

**Currently safe** because `violated` throws, unwinding before corrupted state is observed. **Becomes a bug** when we switch to record-and-continue. Must change to check-then-swap (CAS or pre-check). (#negative-space)

### Finding: `reset-vals!` in done has the same pattern {#done-swap}

**Confidence: confirmed.** `(reset-vals! !done? true)` → old value. If old was `true` → "done called twice". But `!done?` is already `true` (idempotent in this case, no corruption). So this one is benign.

## Thread 3: Violation taxonomy

Each violation classified by: who violates, consumer state at detection, whether it can be surfaced via the protocol.

### Child violations (child calls inner step/done incorrectly)

| Violation                        | Consumer state at detection    | Consumer already stepped?   | Surfaceable?                                  |
|----------------------------------|--------------------------------|-----------------------------|-----------------------------------------------|
| double step                      | stepped (first step forwarded) | yes                         | **yes** — consumer will transfer, throw there |
| step after done                  | received done                  | no (done sent)              | **no** — consumer is terminated               |
| step after crash                 | crashed (prev transfer threw)  | no (can't step after crash) | **no** — rule 4 forbids stepping after crash  |
| done after step without transfer | stepped (step forwarded)       | yes                         | **yes** — consumer will transfer, throw there |
| done called twice                | received done                  | no (done sent)              | **no** — consumer is terminated               |

### Consumer violations (consumer's callback throws during delegation)

| Violation         | Context                     | Surfaceable?                      |
|-------------------|-----------------------------|-----------------------------------|
| step cannot throw | consumer's notifier threw   | **no** — consumer is the violator |
| done cannot throw | consumer's terminator threw | **no** — consumer is the violator |

### Transfer-path violations (in deref — already fine)

| Violation                     | Throws from deref? | Status               |
|-------------------------------|--------------------|----------------------|
| transfer after crash          | yes                | **no change needed** |
| transfer without initial step | yes                | **no change needed** |
| double transfer               | yes                | **no change needed** |
| child deref throws            | yes (crash)        | **no change needed** |

### Finding: only 2 of 7 non-transfer violations are surfaceable {#surfaceable}

**Confidence: confirmed.** Only **double step** and **done after step without transfer** occur when the consumer is already stepped and waiting to transfer. All others happen after the consumer has reached a terminal or post-crash state.

## Thread 4: Pure pass-through is achievable for ALL violations

**Source**: user input. Principle: "adding the enforcer makes no noticeable change to our program."

Previous analysis assumed the enforcer must *react* to violations (drain, surface on transfer, coordinate done). This was wrong. The enforcer only needs to *observe* — detect, report via callback, forward the call as-is.

Verification for every violation:

| Violation | Without enforcer | With pass-through enforcer | Behavior identical? |
|---|---|---|---|
| double step | child's 2nd step hits consumer | detect, callback, forward to consumer | yes |
| step after done | child's step hits consumer after done | detect, callback, forward to consumer | yes |
| step after crash | child's step hits consumer after crash | detect, callback, forward to consumer | yes |
| done after step w/o xfer | child's done hits consumer mid-step | detect, callback, forward to consumer | yes |
| done called twice | child's 2nd done hits consumer | detect, callback, forward to consumer | yes |
| step cannot throw | consumer step throws → child sees throw | detect, callback, re-throw to child | yes |
| done cannot throw | consumer done throws → child sees throw | detect, callback, re-throw to child | yes |

**Confidence: confirmed.** Every violation can be handled as: detect → callback → forward/re-throw. No intervention, no drain, no mode switching.

### Consequence: Threads 4-6 (drain, surfacing on transfer, done ordering) are eliminated.

The earlier decomposition into "surfaceable" vs "unsurfaceable" was unnecessary complexity. All violations use the same mechanism: callback + pass-through.

### Finding: first-violation-wins eliminates state corruption concern {#first-wins}

**Confidence: confirmed.** After detecting the first violation, the enforcer stops checking and becomes pure pass-through. The swap-before-check corruption ({#swap-corrupt}) doesn't matter — the corrupted state is never read again.

### Finding: `cannot-throw` for consumer callbacks = catch + callback + re-throw {#cannot-throw-passthrough}

**Confidence: confirmed.** For step-cannot-throw and done-cannot-throw: the enforcer catches the consumer's exception, reports via callback, then re-throws the original exception so the child sees the same throw it would without the enforcer. The enforcer's step'/done' throws — but this is identical to behavior without the enforcer, satisfying the transparency principle.

(#deep — Layer 3: the enforcer technically commits a protocol violation by re-throwing from step'/done', but this violation is isomorphic to the consumer's original violation. The enforcer doesn't *introduce* new violations — it preserves existing ones.)

## Thread 5: Revised decomposition

| # | Subproblem | Depends on | Notes |
|---|-----------|------------|-------|
| 1 | `cannot-throw` returns either-type | — | Returns `[:ok nil]` or `[:ex e]` instead of throwing |
| 2 | Violation checks call callback instead of throw | 1 | All step'/done' violation paths: callback, then forward |
| 3 | Consumer callback re-throw | 1 | On `[:ex e]`, callback + re-throw original exception |

Subproblem 2 depends on 1. Subproblem 3 depends on 1. No coupling between 2 and 3.

**Rejected subproblems**: drain mode, violation surfacing on transfer, done ordering, first-violation-wins gate — all unnecessary under pure pass-through. First-violation-wins is the callback's concern, not the enforcer's.

## Resolved Research Questions

1. ~~Side channel~~: violation callback provided at construction time.
2. ~~Step-after-crash drain~~: no drain for any violation. Pure pass-through.
3. ~~Surfaceable vs unsurfaceable~~: false distinction. All violations use callback + pass-through.
4. ~~step-cannot-throw treatment~~: catch, callback, re-throw original exception. Transparent.

## Open Research Questions

None. Research threads exhausted.

# Design

## Derivation from constraints

The constraints almost fully determine the design:

- **Pure pass-through** → every wrapper: detect, callback, forward. No intervention.
- **Callback for reporting** → `violated` becomes non-throwing. Constructs ProtocolViolation, passes to callback.
- **`cannot-throw` returns either-type** → consumer callback exceptions caught, reported, re-thrown.

What remains to decide:

## Decision 1: API shape for the callback

Current API:
```clojure
(flow input-flow)                ;; default violated, "" name
(flow nm input-flow)             ;; default violated, custom name
(flow violated nm input-flow)    ;; custom violated fn
```

`violated` currently has signature `(fn [nm msg] ...)` / `(fn [nm msg cause] ...)` and throws.

**Candidate A — Keep current signature, change semantics**: `violated` keeps `(fn [nm msg] ...)` but must not throw. Constructs the exception internally and handles it (store, print, etc.). (#first-principles — the simplest change: just stop throwing.)

- Pro: minimal API change.
- Con: `violated`'s name and multi-arity signature suggest it's a thrower, not a callback. Naming mismatch.

**Candidate B — Callback receives ProtocolViolation exception**: enforcer constructs the exception, passes to callback as `(fn [^ProtocolViolation e] ...)`.

- Pro: single-arity callback is simpler. Enforcer owns exception construction (consistent formatting). Callback just receives and handles.
- Con: caller can't control exception construction (minor — they can wrap it).

**Candidate C — Replace `violated` parameter with a callback parameter**: rename to make intent clear, e.g. `(flow on-violation nm input-flow)`.

- Pro: clear intent.
- Con: API break for the 3-arity (but no external callers use it — research confirmed only 1/2-arity used).

## Decision 2: Default callback (1/2-arity `flow`)

When callers use `(flow nm input-flow)` without providing a callback, what happens on violation?

**Candidate D1 — Throw (current behavior)**: default `violated` still throws. Step/done violations from the default still break the protocol.

- Pro: backward compatible for transfer violations. Tests like `propagator_crash_test.clj` keep working.
- Con: doesn't fix the problem for the default path. Only fixes it for callers who provide a custom callback.

**REJECTED.** Defeats the purpose. The enforcer should be safe by default.

**Candidate D2 — Print**: default callback prints the violation.

- Pro: visible, no behavioral change.
- Con: noisy. May be missed in test output.

**Candidate D3 — Throw (current behavior) in transfer, callback in step/done**: split behavior — `violated` still throws in transfer context, callback in step/done.

- Pro: backward compatible for transfer violations. Fixes the protocol issue in step/done.
- Con: `violated` has context-dependent behavior. More complex internal implementation. (#first-principles — why should the same function behave differently based on call site?)

**Candidate D4 — No default; require callback**: remove 1/2-arity. Force callers to provide a callback.

- Pro: no hidden default to get wrong.
- Con: every call site changes. Invasive.

## Decision 3: Transfer-path violations

Transfer violations (transfer-after-crash, double-transfer, transfer-without-initial-step) currently throw ProtocolViolation from deref, which is protocol-legal (deref can throw).

**Candidate E1 — Keep throwing**: transfer violations still throw ProtocolViolation.

- Pro: these throws are protocol-legal. No reason to remove them.
- Con: inconsistent with "all violations through callback."

**Candidate E2 — Callback + forward**: same as step/done. Callback, then proceed with transfer. Child's result/exception propagates.

- Pro: consistent mechanism for all violations. Pure pass-through.
- Con: `propagator_crash_test.clj` needs updating. Callers lose the ability to catch ProtocolViolation from transfer.

**Candidate E3 — Callback + throw**: callback AND throw ProtocolViolation from deref. Both mechanisms.

- Pro: callback for observability, throw for fail-fast.
- Con: redundant. Throw makes the callback somewhat pointless in transfer context. (#first-principles — if the throw is sufficient, why callback? If callback is sufficient, why throw?)

## Tensions

1. **Default behavior vs safety**: the default should be safe (no protocol violations by the enforcer), but callers like `propagator_crash_test.clj` rely on the enforcer throwing ProtocolViolation. These are in conflict.

2. **Consistency vs pragmatism**: treating all violations identically (callback + forward) is clean. But transfer violations CAN throw — there's no protocol reason to stop. (#deep — Layer 2: the constraint "all violations through callback" was introduced to solve step/done throwing. Applying it to transfer is for consistency, not necessity.)

3. **`violated` as injection point**: the 3-arity exists so the `violated` function can be replaced. This suggests the author (Leo) anticipated different violation-handling strategies. The callback design is a natural evolution of this. (#deep — Layer 3: the 3-arity `flow` is already the callback pattern — it just happens that the default callback throws.)

## Choices

**Decision 1 → C**: rename parameter to `on-violation`, callback receives `ProtocolViolation` exception. Enforcer owns exception construction. Clear intent.

**Decision 2 → D4**: require callback. Remove 1/2-arity convenience overloads. Every caller provides `on-violation` explicitly. **REJECTED: A** (naming mismatch), **B** (minor — subsumed by C). **REJECTED: D1** (defeats purpose), **D2** (hidden default), **D3** (split behavior is complex).

**Decision 3 → E2**: all violations through callback + forward. No ProtocolViolation throws anywhere, including transfer. Pure pass-through for every code path. **REJECTED: E1** (inconsistent — transfer is special-cased for no structural reason), **E3** (redundant dual mechanism).

**Decision 4 → Document only**: callback must not throw synchronously. No safety net in the enforcer. Caller's responsibility. Simpler code.

## Resulting API

```clojure
(flow on-violation nm input-flow)   ;; only arity
```

- `on-violation`: `(fn [^ProtocolViolation e] ...)` — must not throw.
- `nm`: string/symbol/keyword name for the flow (used in exception message).
- `input-flow`: the flow to wrap.

## Resulting behavior per wrapper

**step'** (child calls):
1. Check `!done?`, `!crashed?`, `!should-step?` — detect violation.
2. If violation: `(on-violation (make-violation ...))`. Do NOT update state.
3. Forward to consumer step (call outer step).
4. If consumer step throws: `(on-violation (make-violation ... cause))`, re-throw.

**done'** (child calls):
1. Check `!should-step?`, `!done?` — detect violation.
2. If violation: `(on-violation (make-violation ...))`. Do NOT update state.
3. Forward to consumer done (call outer done).
4. If consumer done throws: `(on-violation (make-violation ... cause))`, re-throw.

**deref** (consumer calls):
1. Check `!crashed?`, `!should-step?` — detect violation.
2. If violation: `(on-violation (make-violation ...))`. Do NOT update state.
3. Forward to child transfer (`@iterator`).
4. If child transfer throws: update `!crashed?`, re-throw (same as current).

**cancel** (consumer calls):
1. Forward to child cancel. If throws: `(on-violation (make-violation ...))`, re-throw.

**constructor** (`input-flow` call):
1. Call `(input-flow step' done')`. If throws: `(on-violation (make-violation ...))`, re-throw. (Constructor context — no protocol constraint on throwing.)
2. Check `!should-step?` for missing initial step. If violation: `(on-violation ...)`.

### State management: check-before-mutate

On violation, state is NOT updated. The swap-before-check pattern is replaced:

```
;; before (throws, so corruption doesn't matter):
(if (first (swap-vals! !should-step? not)) (forward) (violated "double step"))

;; after (no throw, state must stay correct):
(if @!should-step?
  (do (reset! !should-step? false) (forward))
  (do (on-violation ...) (forward)))
```

## Consequences for existing code

- `propagator_crash_test.clj`: expects `ProtocolViolation` thrown from transfer. Must change to provide callback and check violation there.
- All `enforcer/flow` call sites gain an `on-violation` parameter.
- Lincheck test infrastructure (`lincheck.clj`, `lincheck_flow_test.clj`): provide callback that stores first violation (first-writer-wins, like current `violationSeen` pattern).

## Open design questions

None. Ready for spec.

# Spec

## Chosen approach (restated)

The enforcer becomes a pure pass-through observer. All violations are reported via a caller-provided callback. The enforcer constructs the `ProtocolViolation` exception and passes it to the callback. After calling the callback, the enforcer forwards the call to its destination (consumer step/done, child transfer/cancel) as if the enforcer weren't there. The callback must not throw synchronously (documented contract, not enforced).

## Scope

**S1 — New file: `flow_protocol_enforcer2.cljc`.**

New namespace `missionary.flow-protocol-enforcer2`. Single arity:
```clojure
(defn flow [on-violation nm input-flow] ...)
```
- `on-violation`: `(fn [^ProtocolViolation e] ...)`. Must not throw.
- `nm`: string, symbol, or keyword. Used in violation message.
- `input-flow`: the flow to wrap.
- Returns: a flow (fn of `[step done]` → iterator).

The old `flow_protocol_enforcer.cljc` is kept as-is with a comment at the top explaining it violates the protocol on violation detection and pointing to the new namespace.

**S2 — `try-call` helper.**

```clojure
(defn- try-call [f]
  (try (f) nil (catch #?(:clj Throwable :cljs :default) e e)))
```

Returns `nil` on success, the exception on failure. Used for consumer callback delegation (step, done) and cancel. On non-nil result, the caller reports via `on-violation` and re-throws.

**S3 — State checks: check-before-mutate.**

Replace `swap-vals!`-then-check with read-then-conditionally-mutate:

step':
```clojure
(let [s @!should-step?]
  (cond
    @!done?          (on-violation (make-violation nm "step after done"))
    @!crashed?       (on-violation (make-violation nm "step after crash"))
    (not s)          (on-violation (make-violation nm "double step"))
    :else            (reset! !should-step? false))
  ;; forward regardless
  (when-some [e (try-call outer-step)]
    (on-violation (make-violation nm "step cannot throw" e))
    (throw e)))
```

done':
```clojure
(cond
  (false? @!should-step?) (on-violation (make-violation nm "done after step without transfer"))
  @!done?                 (on-violation (make-violation nm "done called twice"))
  :else                   (reset! !done? true))
;; forward regardless
(when-some [e (try-call outer-done)]
  (on-violation (make-violation nm "done cannot throw" e))
  (throw e))
```

deref:
```clojure
(let [s @!should-step?]
  (cond
    @!crashed?   (on-violation (make-violation nm "transfer after crash"))
    s            (on-violation (make-violation nm (if (= ::init s) "transfer without initial step" "double transfer")))
    :else        (reset! !should-step? true))
  ;; forward regardless
  (try @child-iterator
       (catch #?(:clj Throwable :cljs :default) e
         (reset! !crashed? e)
         (throw e))))
```

cancel:
```clojure
(when-some [e (try-call child-cancel)]
  (on-violation (make-violation nm "cancel cannot throw" e))
  (throw e))
```

constructor:
```clojure
(let [iter (try (input-flow step' done')
                (catch #?(:clj Throwable :cljs :default) e
                  (on-violation (make-violation nm "flow process creation threw" e))
                  (throw e)))]
  (when (= ::init @!should-step?) (on-violation (make-violation nm "missing initial step")))
  iter)
```

**S4 — `make-violation` helper.**

```clojure
(defn- make-violation
  ([nm msg]   #?(:clj  (ProtocolViolation. (str (pr-str nm) " flow protocol violation: " msg))
                 :cljs (ex-info (str (pr-str nm) " flow protocol violation: " msg) {})))
  ([nm msg e] #?(:clj  (ProtocolViolation. (str (pr-str nm) " flow protocol violation: " msg) e)
                 :cljs (ex-info (str (pr-str nm) " flow protocol violation: " msg) {} e))))
```

Constructs but does NOT throw. Passed to `on-violation`.

**S5 — Comment on old enforcer.**

Add a comment at the top of `flow_protocol_enforcer.cljc` explaining the protocol violation issue and pointing to `flow-protocol-enforcer2`.

**S6 — No call site updates.** Existing callers continue using the old enforcer. Migration is separate work.

## Deferred

**D1 — `:what-flow-is-this` debug check (line 21-22 of old enforcer).** Kept as-is. Orthogonal.

**D2 — CLJS support.** The `#?` reader conditionals are preserved mechanically. No CLJS-specific testing.

**D3 — Call site migration.** Existing callers stay on old enforcer. Migration to enforcer2 is separate work.

## Constraints

**C1 — Pure pass-through.** Every call forwarded after detection. Observable behavior identical to no enforcer present.

**C2 — No throws from step'/done'/cancel.** Except for re-throwing consumer callback exceptions (which are pass-through — same throw the child would see without the enforcer).

**C3 — Callback contract.** `on-violation` must not throw synchronously. Documented, not enforced.

**C4 — State not mutated on violation.** Check-before-mutate pattern. State only changes on valid operations.

**C5 — Cross-platform.** `.cljc` with reader conditionals for CLJ/CLJS. `ProtocolViolation` (CLJ) vs `ex-info` (CLJS).

## Edge cases

**EC1 — Consumer step/done throws.** `try-call` catches, `on-violation` called with cause, original exception re-thrown. Child sees same throw as without enforcer.

**EC2 — Constructor throws.** `on-violation` called, exception re-thrown. Caller sees same throw.

**EC3 — Multiple violations in sequence.** Each violation calls `on-violation` independently. No first-violation-wins in the enforcer. Callback may implement its own filtering.

**EC4 — Violation on every call.** If child is severely broken (e.g., double-step then step-after-crash then done-called-twice), each violation triggers a separate `on-violation` call. State is preserved correctly because violations don't mutate state (C4).

**EC5 — `on-violation` throws despite contract.** Undefined behavior. The throw escapes into the child or consumer. Documented as caller's fault (C3).

## Resolved questions

**Q1 — `try-call` return convention.** `nil` on success, exception on failure. Checked with `when-some`. No vector allocation on the happy path.

**Q2 — `!v` atom.** Dropped. Unused in the enforcer — never read. (#first-principles — if it's never read, it doesn't exist.)

**Q3 — Call site updates.** Not in scope. Old enforcer kept with explanatory comment. New enforcer is `enforcer2`.

## Open questions

None. Spec complete.
