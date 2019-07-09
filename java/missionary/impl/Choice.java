package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IFn;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Util.NOP;

public final class Choice extends AFn {
    static final AtomicReferenceFieldUpdater<Choice, IFn> TOKEN =
            AtomicReferenceFieldUpdater.newUpdater(Choice.class, IFn.class, "token");

    static final AtomicReferenceFieldUpdater<Choice, IFn> READY =
            AtomicReferenceFieldUpdater.newUpdater(Choice.class, IFn.class, "ready");

    volatile IFn token = NOP;
    volatile IFn ready = NOP;

    Choice parent;
    IFn backtrack;
    Object iterator;
    boolean preemptive;
    boolean done;

    IFn ready() {
        IFn s;
        while (!READY.compareAndSet(this, s = ready, s == null ? NOP : null));
        return s;
    }

    @Override
    public Object invoke() {
        for (;;) {
            IFn c = token;
            if (c == null) break;
            if (TOKEN.compareAndSet(this, c, null)) {
                ((IFn) iterator).invoke();
                c.invoke();
                break;
            }
        }
        return null;
    }
}
