package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class Observe extends AFn implements IDeref {

    static final AtomicReferenceFieldUpdater<Observe, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Observe.class, Object.class, "state");

    static final ExceptionInfo OVERFLOW =
            new ExceptionInfo("Unable to process event : consumer is not ready.",
                    PersistentHashMap.EMPTY);

    IFn notifier;
    IFn terminator;
    IFn unsubscribe;
    Object last = STATE;

    volatile Object state = STATE;

    public Observe(IFn sub, IFn n, IFn t) {
        notifier = n;
        terminator = t;
        unsubscribe = (IFn) sub.invoke(new AFn() {
            @Override
            public Object invoke(Object x) {
                for(;;) {
                    Object s = state;
                    if (s == unsubscribe) break;
                    if (s != STATE) throw OVERFLOW;
                    if (STATE.compareAndSet(Observe.this, s, x)) {
                        notifier.invoke();
                        break;
                    }
                }
                return null;
            }
        });
    }

    @Override
    public Object invoke() {
        for(;;) {
            Object s = state;
            if (s == unsubscribe) break;
            last = s;
            if (STATE.compareAndSet(this, s, unsubscribe)) {
                unsubscribe.invoke();
                if (s == STATE) terminator.invoke();
                break;
            }
        }
        return null;
    }

    @Override
    public Object deref() {
        for(;;) {
            Object s = state;
            if (s == unsubscribe) {
                terminator.invoke();
                return last;
            } else if (STATE.compareAndSet(this, s, STATE)) {
                return s;
            }
        }
    }
}
