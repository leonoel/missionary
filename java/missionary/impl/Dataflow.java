package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Util.NOP;

public interface Dataflow {

    AtomicReferenceFieldUpdater<Port, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Port.class, Object.class, "state");

    final class Port extends AFn implements Event.Emitter {
        static {
            Util.printDefault(Port.class);
        }

        volatile Object state = null;

        @Override
        public Object invoke(Object x) {
            return assign(this, x);
        }

        @Override
        public Object invoke(Object s, Object f) {
            return deref(this, (IFn) s, (IFn) f);
        }

        @Override
        public void cancel(Event e) {
            cancelDeref(this, e);
        }
    }

    static Object assign(Port port, Object x) {
        for(;;) {
            Object s = port.state;
            if (s instanceof Reduced) return ((Reduced) s).deref();
            else if (STATE.compareAndSet(port, s, new Reduced(x))) {
                if (s != null) {
                    Iterator it = RT.iter(s);
                    do ((Event) it.next()).success.invoke(x);
                    while (it.hasNext());
                }
                return x;
            }
        }
    }

    static IFn deref(Port port, IFn success, IFn failure) {
        for(;;) {
            Object s = port.state;
            if (s instanceof Reduced) {
                success.invoke(((Reduced) s).deref());
                return NOP;
            } else {
                Event e = new Event(port, success, failure);
                IPersistentSet set = (s == null) ? PersistentHashSet.EMPTY : (IPersistentSet) s;
                if (STATE.compareAndSet(port, s, set.cons(e))) return e;
            }
        }
    }

    static void cancelDeref(Port port, Event e) {
        for(;;) {
            Object s = port.state;
            if (!(s instanceof IPersistentSet)) break;
            IPersistentSet set = (IPersistentSet) s;
            if (!(set.contains(e))) break;
            if (STATE.compareAndSet(port, s, set.count() == 1 ? null : set.disjoin(e))) {
                e.failure.invoke(new Cancelled("Dataflow variable derefence cancelled."));
                break;
            }
        }
    }

    static Port make() {
        return new Port();
    }
}
