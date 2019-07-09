package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Util.NOP;

public final class Semaphore extends AFn implements Event.Emitter {

    static final AtomicReferenceFieldUpdater<Semaphore, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Semaphore.class, Object.class, "state");

    volatile Object state;

    public Semaphore(int n) {
        state = n;
    }

    @Override
    public Object invoke() {
        for(;;) {
            Object s = state;
            if (s instanceof IPersistentSet) {
                Event e = (Event) RT.iter(s).next();
                IPersistentSet set = (IPersistentSet) s;
                if (STATE.compareAndSet(this, s, set.count() == 1 ? null : set.disjoin(e))) {
                    e.success.invoke(null);
                    break;
                }
            } else if (STATE.compareAndSet(this, s, s == null ? 1 : ((Integer) s) + 1)) break;
        }
        return null;
    }

    @Override
    public Object invoke(Object success, Object failure) {
        for(;;) {
            Object s = state;
            if (s instanceof Integer) {
                int n = (Integer) s;
                if (STATE.compareAndSet(this, s, n == 1 ? null : n - 1)) {
                    ((IFn) success).invoke(null);
                    return NOP;
                }
            } else {
                Event e = new Event(this, (IFn) success, (IFn) failure);
                IPersistentSet set = (s == null) ? PersistentHashSet.EMPTY : (IPersistentSet) s;
                if (STATE.compareAndSet(this, s, set.cons(e))) return e;
            }
        }
    }

    @Override
    public void cancel(Event e) {
        for(;;) {
            Object s = state;
            if (!(s instanceof IPersistentSet)) break;
            IPersistentSet set = (IPersistentSet) s;
            if (!(set.contains(e))) break;
            if (STATE.compareAndSet(this, s, set.count() == 1 ? null : set.disjoin(e))) {
                e.failure.invoke(new ExceptionInfo("Semaphore acquire cancelled.", RT.map(
                        Keyword.intern(null, "cancelled"),
                        Keyword.intern("missionary", "sem-acquire"))));
                break;
            }
        }
    }
}
