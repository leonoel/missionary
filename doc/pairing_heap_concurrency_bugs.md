# Concurrent Pairing Heap: Bugs, Root Causes, and the Packed State Fix

## Overview

The concurrent pairing heap uses two volatile fields (`head` and `tail`) to coordinate
N-writer / 1-reader access. Lincheck found two distinct bugs in this design, both rooted
in the same structural flaw: **reading head and tail as two separate volatile loads creates
a window where accept can complete entirely between the reads, leaving the inserter with
an inconsistent snapshot of state.**

Both bugs were found automatically by Lincheck's linearizability checker, in the version
that already had an earlier fix for a sibling-leak bug (commit `e8648c3`).

### Prelude: The Sibling Leak (Bug 0)

Before Lincheck was integrated, hand-rolled concurrent stress tests found a bug in the
go-to-tail path. When `CAS(tail, t#, x#)` failed, the code retried the loop but left
`x#.sibling` pointing at the stale `t#`. On the next iteration, `x#` could be inserted
with a corrupt sibling chain. The fix: reset sibling to nil on CAS failure.

```clojure
;; Before (6c534c5):
(when-not (v/compare-and-set ~tail o# t# x#)
  (recur))                                      ;; sibling still points to old t#!

;; After (e8648c3):
(when-not (v/compare-and-set ~tail o# t# x#)
  (v/set ~sibling x# nil)                       ;; clean up before retry
  (recur))
```

This fix was necessary but not sufficient — the two bugs below remained.

## Bug 1: NullPointerException (Torn Read)

**Found by**: Lincheck stress test (~14 seconds)

### Lincheck Output

```
= Invalid execution results =
| --------------------------------------------------------- |
|      Thread 1       |              Thread 2               |
| --------------------------------------------------------- |
| insert(1): void     |                                     |
| insert(1): void     |                                     |
| insert(1): void     |                                     |
| --------------------------------------------------------- |
| accept(): [1, 1, 1] | insert(-1): NullPointerException #1 |
| --------------------------------------------------------- |

Exception stack traces:
#1: java.lang.NullPointerException: null
```

### Root Cause

The `insert` macro reads `tail` and `head` in sequence:

```clojure
(let [t# (v/get-volatile ~tail o#)     ;; volatile read 1
      h# (v/get-volatile ~head o#)]    ;; volatile read 2
```

These are individually atomic but **not an atomic pair**. Between the two reads, `accept`
can drain the heap completely:

```
Insert Thread                         Accept Thread
─────────────                         ─────────────
t# = get-volatile(tail) → Node(B)
                                      CAS tail Node(B) → idle    ✓
                                      child[head] = Node(B)
                                      set-volatile head = nil
h# = get-volatile(head) → nil        ← reads nil!
```

The inserter now has `t# = Node(B)` (non-idle, non-nil) and `h# = nil`. It passes
all guards (`t# != idle`, `h# != t#`) and reaches `lt(nil, x#)` which reads `.-id` on
`nil` — **NullPointerException**.

### Why the Guards Don't Catch It

The original code had a guard `(nil? h#)` on the idle→first-insert path, but the
become-head and go-to-tail paths assumed `h#` was a valid node whenever `t#` was non-idle.
That assumption breaks when accept drains between the two reads.

## Bug 2: Element Loss via ABA (Stale CAS)

**Found by**: Lincheck model checking (~164 seconds, full interleaving exploration)

### Lincheck Output

```
= Invalid execution results =
| ------------------------------------ |
|     Thread 1      |     Thread 2     |
| ------------------------------------ |
| insert(0): void   |                  |
| ------------------------------------ |
| accept(): [0]     | insert(-1): void |
| insert(-2): void  |                  |
| ------------------------------------ |
| accept(): [-1, 0] |                  |
| ------------------------------------ |
```

The second accept returns `[−1, 0]` but Node(0) was already consumed by the first accept.
Node(−2) is lost entirely. No sequential ordering of these operations can produce this result.

### Root Cause: ABA on `nil`

`nil` is a singleton in the JVM. `CAS(tail, nil, X)` succeeds whenever tail *happens to be*
`nil`, regardless of how many state transitions occurred since the original read.

The vulnerable code is the "become head" path:

```clojure
(if (v/compare-and-set ~tail o# t# h#)   ;; CAS tail: t# → h# (demote head to tail)
  (do (v/set ~child h# t#)               ;; link head's children to old tail
      (v/set-volatile ~head o# x#))       ;; promote x# — blindly overwrites head
  (recur))
```

The CAS only validates `tail`, but the operation also depends on `head` being unchanged.

### Precise Interleaving

```
T2 (insert -1)                        T1 (accept + insert -2)
──────────────                        ────────────────────────
Read h#=Node(0), t#=nil
  [PREEMPTED]
                                      accept():
                                        CAS tail nil → idle       ✓
                                        child[Node(0)] = nil
                                        head = nil
                                        → returns [0]

                                      insert(-2):
                                        tail=idle, head=nil
                                        CAS tail idle → nil       ✓
                                        head = Node(-2)
                                        → state: head=Node(-2), tail=nil

  [RESUMES with stale h#=Node(0), t#=nil]
  lt(Node(0), Node(-1)) → false
  → enters "become head" path
  CAS tail nil → Node(0)              ← ABA! tail cycled nil→idle→nil
  child[Node(0)] = nil                ← writing to already-drained node
  head = Node(-1)                     ← OVERWRITES Node(-2) — LOST!
  → state: head=Node(-1), tail=Node(0)  ← Node(0) is a zombie
```

Post-state: accept returns `[-1, 0]` — Node(0) reappears from the dead, Node(-2) is gone.

## Fix Options Considered

### Option A: CAS on Head (Validate Head After Tail CAS)

Replace `set-volatile head` with `compare-and-set head`:

```clojure
(if (v/compare-and-set ~tail o# t# h#)
  (if (v/compare-and-set ~head o# h# x#)   ;; atomic!
    (v/set ~child h# t#)
    (do (v/compare-and-set ~tail o# h# t#)  ;; undo
        (recur)))
  (recur))
```

**Rejected**: Critical window between the two CASes. After insert CASes tail but before it
CASes head, accept can CAS tail to `idle`, read a valid `head != tail`, and proceed — linking
stale nodes as children and corrupting the heap. The assumption that accept would see `h# == t#`
and spin was wrong: accept reads `head` *after* the value has potentially changed.

### Option B: Always Append to Tail

Eliminate the "become head" path entirely. Always insert at the tail, fix up the minimum
during accept.

**Rejected**: Breaks the invariant that head is always the minimum. Accept would need to scan
the tail chain for the true minimum, changing O(1) drain to O(n) scan and fundamentally altering
the data structure's semantics.

### Option C: Epoch Counter

Add a volatile generation counter incremented on each accept. Insert checks it before and after
each CAS attempt.

**Rejected**: Same window problem as Option A — the epoch check happens after the tail CAS,
which is too late. Accept can complete between tail CAS and epoch check.

### `nil? t#` Spin Guard

Add `(nil? t#)` to the spin guard so the thread yields when tail is nil:

```clojure
(if (or (identical? h# t#) (nil? h#) (nil? t#))
  (do (u/yield) (recur))
  ...)
```

**Rejected**: Causes **livelock**. Lincheck found it immediately:

```
= The execution has hung =
| ----------------- |
|     Thread 1      |
| ----------------- |
| insert(1): void   |
| ----------------- |
| insert(1): <hung> |
| ----------------- |

The following interleaving leads to the error:
|     Thread 1                                                  |
| insert(1)                                                     |
| insert(1): <hung>                                             |
|   /* The following events repeat infinitely: */               |
|   > root -> Object#1                                          |
|   | root -> util$yield#1                                      |
|   < switch (reason: active lock detected)                     |
```

After the first insert, state is `tail=nil, head=Node(1)`. The `nil? t#` guard traps the
second insert in an infinite spin because nothing will ever change tail from nil — only accept
does that, and nobody is calling accept.

## The Fix: Packed State

All rejected options share one fundamental problem: **two separate fields cannot be read or
written atomically**. Any fix that leaves them separate has a window between the operations.

### Design

Replace the two volatile fields (`head`, `tail`) with a single `state` field:

```
state: idle                          → heap is empty
state: HeapState(head, tail)         → heap has elements
```

`HeapState` is an immutable `deftype` with final fields. Each instance is a unique Java
object. CAS on `state` compares **object identity** — two HeapState objects with the same
head/tail values inside are still different objects. **ABA is impossible by construction.**

### State Machine

```
state: idle ──CAS──► HeapState(x,nil) ──CAS──► HeapState(h,t) ──CAS──► idle
        ▲       first insert         tail grows        accept drains    │
        └──────────────────────────────────────────────────────────────┘
```

### How It Fixes Bug 1 (NPE / Torn Read)

Head and tail are read from the **same HeapState object**:

```clojure
(let [s# (v/get-volatile ~state o#)]
  ;; ...
  (let [h# (v/get ~head s#)     ;; plain read — same object
        t# (v/get ~tail s#)]))  ;; plain read — same object
```

No interleaving is possible between these reads because they access fields of an immutable
object that was captured by a single volatile load. The snapshot is **atomic by construction**.

### How It Fixes Bug 2 (ABA)

CAS compares the HeapState reference, not the values inside it:

```clojure
(v/compare-and-set ~state o# s# (~mk-state h# x#))
```

Even if a later HeapState happens to contain the same head and tail values, it's a different
object. The CAS fails, and the inserter retries with a fresh snapshot.

### Two-Phase Commit for Become-Head

The become-head path must atomically: set head=x, set tail=h, and set child[h]=t. With packed
state we can atomically set head+tail in one CAS, but `child[h]=t` is a separate write.

Solution — leverage the existing `h == t` spin guard:

```clojure
;; Phase 1: CAS to intermediate state where head == tail → all others spin
(if (v/compare-and-set ~state o# s# (~mk-state h# h#))
  (do
    ;; Exclusive window: set child linkage
    (v/set ~child h# t#)
    ;; Phase 2: publish new state via set-volatile
    (v/set-volatile ~state o# (~mk-state x# h#)))
  (recur))
```

1. **Phase 1**: CAS to `HeapState(h, h)` — every other thread sees head==tail and spins
2. **Exclusive window**: safely set `child[h] = t` (nobody else touches state)
3. **Phase 2**: `set-volatile` publishes `HeapState(x, h)` — volatile ordering guarantees
   the child linkage is visible to any thread that subsequently reads the new state

### Memory Ordering

- HeapState fields are **final** (immutable deftype) — the JMM guarantees visibility of
  final fields after the constructor completes, provided the reference is published via
  a volatile store
- `set-volatile` in phase 2 **happens-after** `child[h]=t` in program order; any thread that
  reads the new state via `get-volatile` will see the child linkage
- Accept's `child[h]=t` after CAS to `idle` is single-threaded (consumer owns the nodes),
  so program order suffices

### Tradeoff

The packed state approach allocates a new HeapState object on every state transition. This is
a small cost for **correctness by construction** — the bugs are structurally impossible, not
just empirically absent.

## Verification

After implementing packed state, all tests pass:

| Test | Result | Duration |
|------|--------|----------|
| Clojure unit + concurrent tests (205 tests, 5194 assertions) | 0 failures | ~seconds |
| Lincheck stress test (50 iterations, 3 threads, 4 actors) | passed | ~20s |
| Lincheck model checking (50 iterations, 3 threads, 3 actors) | passed | ~333s |

The model checking test is particularly significant: it systematically explores thread
interleavings and confirmed that no linearizability violation exists in the packed state
implementation.
