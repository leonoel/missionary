package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Util.NOP;

public final class Mailbox extends AFn implements Event.Emitter {

    static final AtomicReferenceFieldUpdater<Mailbox, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Mailbox.class, Object.class, "state");

    volatile Object state = null;

    public Mailbox() {
    }

    @Override
    public Object invoke(Object msg) {
        for(;;) {
            Object s = state;
            if (s instanceof IPersistentSet) {
                Event e = (Event) RT.iter(s).next();
                IPersistentSet set = (IPersistentSet) s;
                if (STATE.compareAndSet(this, s, set.count() == 1 ? null : set.disjoin(e))) {
                    e.success.invoke(msg);
                    break;
                }
            } else if (STATE.compareAndSet(this, s, s == null ?
                    PersistentVector.create(msg) : ((IPersistentVector) s).cons(msg))) break;
        }
        return null;
    }

    @Override
    public Object invoke(Object success, Object failure) {
        for(;;) {
            Object s = state;
            if (s instanceof IPersistentVector) {
                IPersistentVector v = (IPersistentVector) s;
                int n = v.count();
                if (STATE.compareAndSet(this, s, n == 1 ? null :
                        new APersistentVector.SubVector(null, v, 1, n))) {
                    ((IFn) success).invoke(v.nth(0));
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
                e.failure.invoke(new ExceptionInfo("Mailbox fetch cancelled.", RT.map(
                        Keyword.intern(null, "cancelled"),
                        Keyword.intern("missionary", "mbx-fetch"))));
                break;
            }
        }
    }
}
