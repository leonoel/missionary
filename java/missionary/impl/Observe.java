package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Util.NOP;

public final class Observe extends AFn implements IDeref {

    static final AtomicReferenceFieldUpdater<Observe, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Observe.class, Object.class, "state");

    static final ExceptionInfo OVERFLOW =
            new ExceptionInfo("Unable to process event : consumer is not ready.",
                    PersistentHashMap.EMPTY);

    IFn notifier;
    IFn terminator;
    Object unsubscribe;
    boolean failed;
    Object last = STATE;

    volatile Object state = STATE;

    void unsub() {
        try {
            ((IFn) unsubscribe).invoke();
            terminator.invoke();
        } catch (Throwable e) {
            failed = true;
            last = e;
            notifier.invoke();
        }
    }

    public Observe(IFn sub, IFn n, IFn t) {
        notifier = n;
        terminator = t;
        try {
            unsubscribe = sub.invoke(new AFn() {
                @Override
                public Object invoke(Object x) {
                    for(;;) {
                        Object s = state;
                        if (s == NOP) break;
                        if (s != STATE) throw OVERFLOW;
                        if (STATE.compareAndSet(Observe.this, s, x)) {
                            notifier.invoke();
                            break;
                        }
                    }
                    return null;
                }
            });
        } catch (Throwable e) {
            failed = true;
            last = e;
            Object s;
            while (!STATE.compareAndSet(this, s = state, NOP));
            if (s == STATE) notifier.invoke();
        }
    }

    @Override
    public Object invoke() {
        for(;;) {
            Object s = state;
            if (s == NOP) break;
            last = s;
            if (STATE.compareAndSet(this, s, NOP)) {
                if (s == STATE) unsub();
                break;
            }
        }
        return null;
    }

    @Override
    public Object deref() {
        for(;;) {
            Object s = state;
            if (s == NOP) {
                if (failed) {
                    terminator.invoke();
                    return clojure.lang.Util.sneakyThrow((Throwable) last);
                } else {
                    Object x = last;
                    unsub();
                    return x;
                }
            } else if (STATE.compareAndSet(this, s, STATE)) {
                return s;
            }
        }
    }
}
