package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Util.NOP;

public final class Dataflow extends AFn implements Event.Emitter {

    static final AtomicReferenceFieldUpdater<Dataflow, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Dataflow.class, Object.class, "state");

    volatile Object state = null;

    @Override
    public Object invoke(Object x) {
        for(;;) {
            Object s = state;
            if (s instanceof Reduced) return ((Reduced) s).deref();
            else if (STATE.compareAndSet(this, s, new Reduced(x))) {
                if (s != null) {
                    Iterator it = RT.iter(s);
                    do ((Event) it.next()).success.invoke(x);
                    while (it.hasNext());
                }
                return x;
            }
        }
    }

    @Override
    public Object invoke(Object success, Object failure) {
        for(;;) {
            Object s = state;
            if (s instanceof Reduced) {
                ((IFn) success).invoke(((Reduced) s).deref());
                return NOP;
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
                e.failure.invoke(new Cancelled("Dataflow variable derefence cancelled."));
                break;
            }
        }
    }
}
