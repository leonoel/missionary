package missionary;

import clojure.java.api.Clojure;
import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.PersistentVector;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lincheck stress test harness for missionary flow operators.
 *
 * Architecture:
 *   DummyFlow(s) → enforcer → [Operator Under Test] → enforcer → RootConsumer
 *
 * All flows are wrapped with missionary.flow-protocol-enforcer/flow.
 *
 * Concurrency model:
 *   - step/done/crash on the same DummyFlow are serialized via nonParallelGroup
 *     (one group per flow index: "f0", "f1", "f2", "f3").
 *   - transfer is in group "consumer" (single consumer per spec).
 *   - cancel has no group (can run concurrently with anything).
 *   - step/done on DIFFERENT flows CAN run concurrently.
 *   - step/done CAN run concurrently with transfer (cross-group).
 *
 * Coverage notes — scenarios NOT tested and why:
 *   - Behavioral correctness (output values match inputs): crash/protocol test
 *     only. Lolcat tests cover behavioral semantics.
 *   - Lifecycle completion (operator eventually terminates all inputs after
 *     cancel): no @Validate in Lincheck stress mode.
 *   - True same-thread re-entrancy (callback fires during operator's own
 *     transfer call stack): DummyFlow.deref is simple (no re-entrant
 *     callbacks). Concurrent step/done from Lincheck threads exercises the
 *     same operator CAS-toggle code paths. Lolcat tests cover true
 *     re-entrancy with muppet flows.
 */
public class FlowLincheckTest {

    // ── Operator factory ─────────────────────────────────────────────

    @FunctionalInterface
    interface FlowFactory {
        Object create(DummyFlow[] flows);
    }

    // ── Clojure bridge ──────────────────────────────────────────────

    static final IFn VECTOR, IDENTITY, CONJ, APPLY, ENFORCE_FLOW;
    static final IFn MC_LATEST, MC_ZIP, MC_SAMPLE, MC_REDUCTIONS, MC_EDUCTION, MC_RELIEVE, MC_BUFFER;
    static {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("missionary.core"));
        require.invoke(Clojure.read("missionary.flow-protocol-enforcer"));

        VECTOR   = Clojure.var("clojure.core", "vector");
        IDENTITY = Clojure.var("clojure.core", "identity");
        CONJ     = Clojure.var("clojure.core", "conj");
        APPLY    = Clojure.var("clojure.core", "apply");

        ENFORCE_FLOW = Clojure.var("missionary.flow-protocol-enforcer", "flow");

        MC_LATEST     = Clojure.var("missionary.core", "latest");
        MC_ZIP        = Clojure.var("missionary.core", "zip");
        MC_SAMPLE     = Clojure.var("missionary.core", "sample");
        MC_REDUCTIONS = Clojure.var("missionary.core", "reductions");
        MC_EDUCTION   = Clojure.var("missionary.core", "eduction");
        MC_RELIEVE    = Clojure.var("missionary.core", "relieve");
        MC_BUFFER     = Clojure.var("missionary.core", "buffer");
    }

    static IFn combinator(int n) { return n == 1 ? IDENTITY : VECTOR; }

    static Object enforce(String name, Object flow) {
        return ENFORCE_FLOW.invoke(name, flow);
    }

    // ── Predefined factories ────────────────────────────────────────

    static final FlowFactory LATEST = flows -> {
        Object[] wrapped = wrapInputs(flows, "latest");
        return enforce("latest-out",
            APPLY.invoke(MC_LATEST, combinator(wrapped.length),
                PersistentVector.create(Arrays.asList(wrapped))));
    };

    static final FlowFactory ZIP = flows -> {
        Object[] wrapped = wrapInputs(flows, "zip");
        return enforce("zip-out",
            APPLY.invoke(MC_ZIP, combinator(wrapped.length),
                PersistentVector.create(Arrays.asList(wrapped))));
    };

    static final FlowFactory SAMPLE = flows -> {
        Object[] wrapped = wrapInputs(flows, "sample");
        return enforce("sample-out",
            APPLY.invoke(MC_SAMPLE, combinator(wrapped.length),
                PersistentVector.create(Arrays.asList(wrapped))));
    };

    static final FlowFactory REDUCTIONS = flows -> {
        Object[] wrapped = wrapInputs(flows, "reductions");
        return enforce("reductions-out",
            MC_REDUCTIONS.invoke(CONJ, wrapped[0]));
    };

    static final FlowFactory EDUCTION = flows -> {
        Object[] wrapped = wrapInputs(flows, "eduction");
        return enforce("eduction-out",
            MC_EDUCTION.invoke(
                Clojure.var("clojure.core", "map").invoke(IDENTITY),
                wrapped[0]));
    };

    static final FlowFactory RELIEVE = flows -> {
        Object[] wrapped = wrapInputs(flows, "relieve");
        return enforce("relieve-out",
            MC_RELIEVE.invoke(wrapped[0]));
    };

    static final FlowFactory BUFFER = flows -> {
        Object[] wrapped = wrapInputs(flows, "buffer");
        return enforce("buffer-out",
            MC_BUFFER.invoke(4, wrapped[0]));
    };

    static Object[] wrapInputs(DummyFlow[] flows, String opName) {
        Object[] wrapped = new Object[flows.length];
        for (int i = 0; i < flows.length; i++)
            wrapped[i] = enforce(opName + "-in-" + i, flows[i]);
        return wrapped;
    }

    // ── Static configuration (set before check()) ───────────────────

    static FlowFactory factory;
    static int flowCount;

    // ── Root consumer states ────────────────────────────────────────

    static final int TRANSFERRED = 0;
    static final int STEPPED = 1;
    static final int CLAIMED = 2;
    static final int STEPPED_DURING_TRANSFER = 3;
    static final int DONE = 4;

    // ── Instance state (fresh per Lincheck scenario) ────────────────

    final DummyFlow[] flows;
    final AtomicInteger rootState = new AtomicInteger(TRANSFERRED);
    final Object iterator;
    volatile boolean terminated = false;

    public FlowLincheckTest() {
        if (factory == null) {
            flows = null;
            iterator = null;
            return;
        }

        flows = new DummyFlow[flowCount];
        for (int i = 0; i < flowCount; i++)
            flows[i] = new DummyFlow();

        Object flow = factory.create(flows);

        IFn rootStep = new AFn() {
            public Object invoke() {
                rootState.getAndUpdate(s -> {
                    switch (s) {
                        case TRANSFERRED: return STEPPED;
                        case CLAIMED:     return STEPPED_DURING_TRANSFER;
                        case DONE:  return DONE;
                        default:
                            throw new AssertionError(
                                "Protocol violation: step in state "
                                + stateName(s));
                    }
                });
                return null;
            }
        };

        IFn rootDone = new AFn() {
            public Object invoke() {
                terminated = true;
                rootState.getAndUpdate(s -> {
                    switch (s) {
                        case TRANSFERRED: return DONE;
                        case DONE:        return DONE;
                        default:          return s;  // keep STEPPED/CLAIMED/SDT
                    }
                });
                return null;
            }
        };

        iterator = ((IFn) flow).invoke(rootStep, rootDone);
    }

    // ── Operations: step/done per flow (nonParallelGroup serialized) ─

    @Operation(nonParallelGroup = "f0") public String step0() { return flowCount > 0 ? flows[0].step() : "n/a"; }
    @Operation(nonParallelGroup = "f1") public String step1() { return flowCount > 1 ? flows[1].step() : "n/a"; }
    @Operation(nonParallelGroup = "f2") public String step2() { return flowCount > 2 ? flows[2].step() : "n/a"; }
    @Operation(nonParallelGroup = "f3") public String step3() { return flowCount > 3 ? flows[3].step() : "n/a"; }

    @Operation(nonParallelGroup = "f0") public String done0() { return flowCount > 0 ? flows[0].done() : "n/a"; }
    @Operation(nonParallelGroup = "f1") public String done1() { return flowCount > 1 ? flows[1].done() : "n/a"; }
    @Operation(nonParallelGroup = "f2") public String done2() { return flowCount > 2 ? flows[2].done() : "n/a"; }
    @Operation(nonParallelGroup = "f3") public String done3() { return flowCount > 3 ? flows[3].done() : "n/a"; }

    @Operation(nonParallelGroup = "f0") public String crash0() { return flowCount > 0 ? flows[0].setThrow() : "n/a"; }
    @Operation(nonParallelGroup = "f1") public String crash1() { return flowCount > 1 ? flows[1].setThrow() : "n/a"; }
    @Operation(nonParallelGroup = "f2") public String crash2() { return flowCount > 2 ? flows[2].setThrow() : "n/a"; }
    @Operation(nonParallelGroup = "f3") public String crash3() { return flowCount > 3 ? flows[3].setThrow() : "n/a"; }

    // ── Operation: transfer (root consumer) ─────────────────────────

    @Operation(nonParallelGroup = "consumer")
    public Object transfer() {
        if (terminated) return "skip";
        int old = rootState.get();
        if (old != STEPPED) return "skip";
        if (!rootState.compareAndSet(old, CLAIMED)) return "skip";

        Object ret;
        try {
            ret = ((IDeref) iterator).deref();
        } catch (ProtocolViolation e) {
            throw e;
        } catch (Exception e) {
            ret = "err:" + e.getClass().getSimpleName();
        }

        rootState.getAndUpdate(post -> {
            switch (post) {
                case CLAIMED:                 return TRANSFERRED;
                case STEPPED_DURING_TRANSFER: return STEPPED;
                case DONE:              return DONE;
                default:
                    throw new AssertionError(
                        "Protocol violation: post-transfer state "
                        + stateName(post));
            }
        });
        return ret;
    }

    // ── Operation: cancel (root consumer) ───────────────────────────

    @Operation
    public void cancel() {
        if (terminated) return;
        ((IFn) iterator).invoke();
        return;
    }

    // ── Test API ─────────────────────────────────────────────────────

    static int prop(String name, int fallback) {
        String v = System.getProperty("missionary.lincheck." + name);
        return v != null ? Integer.parseInt(v) : fallback;
    }

    static void stressTest(FlowFactory f, int n) {
        factory = f;
        flowCount = n;
        new StressOptions()
            .iterations(prop("iterations", 100))
            .threads(prop("threads", 2))
            .actorsPerThread(prop("actorsPerThread", 4))
            .invocationsPerIteration(prop("invocationsPerIteration", 200))
            .check(FlowLincheckTest.class);
    }

    // ── Test entry points ───────────────────────────────────────────

    @Test public void testLatest0() { stressTest(LATEST, 0); }
    @Test public void testLatest1() { stressTest(LATEST, 1); }
    @Test public void testLatest2() { stressTest(LATEST, 2); }
    @Test public void testLatest3() { stressTest(LATEST, 3); }
    @Test public void testLatest4() { stressTest(LATEST, 4); }

    @Test public void testZip1() { stressTest(ZIP, 1); }
    @Test public void testZip2() { stressTest(ZIP, 2); }
    @Test public void testZip3() { stressTest(ZIP, 3); }
    @Test public void testZip4() { stressTest(ZIP, 4); }

    @Test public void testSample2() { stressTest(SAMPLE, 2); }
    @Test public void testSample3() { stressTest(SAMPLE, 3); }
    @Test public void testSample4() { stressTest(SAMPLE, 4); }

    @Test public void testReductions() { stressTest(REDUCTIONS, 1); }
    @Test public void testEduction()   { stressTest(EDUCTION, 1); }
    @Test public void testRelieve()    { stressTest(RELIEVE, 1); }
    @Test public void testBuffer()     { stressTest(BUFFER, 1); }

    // ── Helpers ──────────────────────────────────────────────────────

    static String stateName(int s) {
        switch (s) {
            case TRANSFERRED: return "TRANSFERRED";
            case STEPPED: return "STEPPED";
            case CLAIMED: return "CLAIMED";
            case STEPPED_DURING_TRANSFER: return "STEPPED_DURING_TRANSFER";
            case DONE: return "DONE";
            default: return "UNKNOWN(" + s + ")";
        }
    }
}
