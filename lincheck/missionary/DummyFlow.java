package missionary;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controllable dummy flow for Lincheck testing of missionary operators.
 *
 * Implements the flow protocol: 2-arity invoke(step, done) → iterator.
 * The iterator supports cancel (invoke) and transfer (deref).
 *
 * External control:
 *   step() — signal a new value is ready (CAS-guarded, respects DONE/CANCELLED/CRASHED)
 *   done() — signal termination (CAS-guarded, at most once, callable after crash)
 *
 * step() and done() on the same DummyFlow are serialized by Lincheck's
 * nonParallelGroup (one group per flow). deref() runs on the consumer
 * thread (different group), so state access uses AtomicInteger for
 * cross-thread visibility.
 *
 * Protocol invariant: STEPPED remains set throughout deref(), preventing
 * concurrent step() from firing mid-transfer. STEPPED is cleared only at
 * the end of a successful transfer. On crash, CRASHED is set instead
 * (permanently blocking step). This matches the flow protocol: each
 * transfer is atomic, the producer must not step until the transfer succeeds.
 */
public class DummyFlow extends AFn {

    /** Thrown by DummyFlow during transfer to simulate upstream errors. */
    public static class IntendedCrash extends RuntimeException {
        public IntendedCrash() { super("intended crash"); }
    }

    // State bit flags
    static final int STEPPED   = 1;  // step called since last transfer
    static final int DONE      = 2;  // done called, flow terminated
    static final int CANCELLED = 4;  // cancel invoked by operator
    static final int CRASHED   = 8;  // transfer threw, no more step allowed

    final AtomicInteger state = new AtomicInteger(0);
    volatile boolean shouldThrow;
    int transferCount;

    // Callbacks provided by operator during subscribe
    volatile IFn stepCb;
    volatile IFn doneCb;

    public DummyFlow() {}

    public String setThrow() { this.shouldThrow = true; return "armed"; }

    // ── Flow protocol: subscribe ──────────────────────────────────────

    /** Called by operator: invoke(step, done) → iterator */
    @Override
    public Object invoke(Object step, Object done) {
        this.stepCb = (IFn) step;
        this.doneCb = (IFn) done;
        state.getAndUpdate(s -> s | STEPPED);
        stepCb.invoke();
        return new Iter();
    }

    // ── External control ─────────────────────────────────────────────

    /**
     * Externally trigger step. CAS-guarded: no step if already STEPPED,
     * DONE, CANCELLED, or CRASHED. Serialized with done() via nonParallelGroup.
     */
    public String step() {
        int old;
        do {
            old = state.get();
            if ((old & (STEPPED | DONE | CANCELLED | CRASHED)) != 0)
                return "no:" + blockReason(old);
        } while (!state.compareAndSet(old, old | STEPPED));
        stepCb.invoke();
        return "ok";
    }

    /**
     * Externally trigger termination. CAS-guarded: at most once.
     * Serialized with step() via nonParallelGroup.
     */
    public String done() {
        int old;
        do {
            old = state.get();
            if ((old & DONE) != 0) return "no:already-done";
        } while (!state.compareAndSet(old, old | DONE));
        doneCb.invoke();
        return "ok";
    }

    static String blockReason(int s) {
        if ((s & CRASHED)   != 0) return "crashed";
        if ((s & DONE)      != 0) return "done";
        if ((s & CANCELLED) != 0) return "cancelled";
        if ((s & STEPPED)   != 0) return "stepped";
        return "unknown";
    }

    // ── Iterator ──────────────────────────────────────────────────────

    /** The iterator returned to the operator on subscribe. */
    public class Iter extends AFn implements IDeref {

        /** Cancel (idempotent). */
        @Override
        public Object invoke() {
            state.getAndUpdate(s -> s | CANCELLED);
            return null;
        }

        /**
         * Transfer: called by operator to pull a value.
         *
         * STEPPED remains set throughout this method, blocking concurrent
         * step(). On success, STEPPED is cleared at the end. On crash,
         * CRASHED is set instead (permanently blocking step).
         */
        @Override
        public Object deref() {
            if (shouldThrow) {
                state.getAndUpdate(s -> (s & ~STEPPED) | CRASHED);
                throw new DummyFlow.IntendedCrash();
            }
            transferCount++;
            int value = (transferCount + 1) % 3 == 0 ? 0 : transferCount % 3;
            state.getAndUpdate(s -> s & ~STEPPED);
            return value;
        }
    }
}
