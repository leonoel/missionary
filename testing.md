# Running Lincheck Tests

Lincheck tests verify linearizability of the concurrent pairing heap
by generating random scenarios of insert/accept operations and checking
that all concurrent results can be explained by some sequential ordering.

## Dependencies

- JDK 17+
- Clojure deps resolved via `clj -A:dev:lincheck`

## Compile

```bash
mkdir -p lincheck-classes
javac -cp "$(clj -A:dev:lincheck -Spath)" \
  -d lincheck-classes \
  lincheck/missionary/PairingHeapLincheckTest.java \
  lincheck/missionary/PairingHeapSequential.java
```

## Run

```bash
java -cp "$(clj -A:dev:lincheck -Spath)" \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -XX:+EnableDynamicAgentLoading \
  org.junit.platform.console.ConsoleLauncher execute \
  -c missionary.PairingHeapLincheckTest 2>/dev/null
```

The `2>/dev/null` suppresses ASM transformation warnings on stderr.

To run a specific test method:

```bash
java -cp "$(clj -A:dev:lincheck -Spath)" \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -XX:+EnableDynamicAgentLoading \
  org.junit.platform.console.ConsoleLauncher execute \
  -m "missionary.PairingHeapLincheckTest#stressTest" 2>/dev/null
```

## Tests

| Test | Mode | Finds | Time |
|------|------|-------|------|
| `stressTest` | Real threads, random scheduling | NPE on concurrent insert during accept | ~12s |
| `modelCheckingTest` | Systematic interleaving exploration | Element loss/duplication with full interleaving trace | ~164s |

## Design

The wrapper does NOT use the `ready?` atom from `pairing-heap-test-impl`. The heap's
own internal state (tail field) handles synchronization — `accept` on an empty heap
throws `Error("Illegal state - empty heap")` which the wrapper catches and returns
`null`. This avoids introducing a separate coordination mechanism whose gap between
insert-CAS and ready-callback would cause spurious linearizability violations.

The sequential spec (`PairingHeapSequential`) is a sorted list: `insert` appends,
`accept` drains and sorts (or returns `null` if empty).

`nonParallelGroup = "reader"` on accept enforces the N-writer-1-reader protocol.

## Notes

- Lincheck 3.4 bundles ASM 9.6. The `:lincheck` alias overrides ASM to 9.9.1
  for Java 25 support. A non-fatal `MethodTooLargeException` on `clojure.lang.AFn`
  appears on stderr (Lincheck skips that class) but does not affect test results.
