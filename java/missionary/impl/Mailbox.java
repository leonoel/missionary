package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Util.NOP;

public interface Mailbox {

    AtomicReferenceFieldUpdater<Port, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Port.class, Object.class, "state");

    final class Port extends AFn implements Event.Emitter {

        volatile Object state = null;

        @Override
        public Object invoke(Object x) {
            post(this, x);
            return null;
        }

        @Override
        public Object invoke(Object s, Object f) {
            return fetch(this, (IFn) s, (IFn) f);
        }

        @Override
        public void cancel(Event e) {
            cancelFetch(this, e);
        }
    }
    static void post(Port port, Object x) {
        for(;;) {
            Object s = port.state;
            if (s instanceof IPersistentSet) {
                Event e = (Event) RT.iter(s).next();
                IPersistentSet set = (IPersistentSet) s;
                if (STATE.compareAndSet(port, s, set.count() == 1 ? null : set.disjoin(e))) {
                    e.success.invoke(x);
                    break;
                }
            } else if (STATE.compareAndSet(port, s, s == null ?
                    PersistentVector.create(x) : ((IPersistentVector) s).cons(x))) break;
        }
    }

    static Object fetch(Port port, IFn success, IFn failure) {
        for(;;) {
            Object s = port.state;
            if (s instanceof IPersistentVector) {
                IPersistentVector v = (IPersistentVector) s;
                int n = v.count();
                if (STATE.compareAndSet(port, s, n == 1 ? null :
                        new APersistentVector.SubVector(null, v, 1, n))) {
                    success.invoke(v.nth(0));
                    return NOP;
                }
            } else {
                Event e = new Event(port, success, failure);
                IPersistentSet set = (s == null) ? PersistentHashSet.EMPTY : (IPersistentSet) s;
                if (STATE.compareAndSet(port, s, set.cons(e))) return e;
            }
        }
    }

    static void cancelFetch(Port port, Event e) {
        for(;;) {
            Object s = port.state;
            if (!(s instanceof IPersistentSet)) break;
            IPersistentSet set = (IPersistentSet) s;
            if (!(set.contains(e))) break;
            if (STATE.compareAndSet(port, s, set.count() == 1 ? null : set.disjoin(e))) {
                e.failure.invoke(new Cancelled("Mailbox fetch cancelled."));
                break;
            }
        }
    }

    static Port make() {
        return new Port();
    }
}

