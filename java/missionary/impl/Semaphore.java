package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Util.NOP;

public interface Semaphore {

    AtomicReferenceFieldUpdater<Port, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Port.class, Object.class, "state");

    final class Port extends AFn implements Event.Emitter {
        static {
            Util.printDefault(Port.class);
        }

        volatile Object state;

        Port(int n) {
            state = n;
        }

        @Override
        public Object invoke() {
            release(this);
            return null;
        }

        @Override
        public Object invoke(Object s, Object f) {
            return acquire(this, (IFn) s, (IFn) f);
        }

        @Override
        public void cancel(Event e) {
            cancelAcquire(this, e);
        }
    }

    static void release(Port port) {
        for(;;) {
            Object s = port.state;
            if (s instanceof IPersistentSet) {
                Event e = (Event) RT.iter(s).next();
                IPersistentSet set = (IPersistentSet) s;
                if (STATE.compareAndSet(port, s, set.count() == 1 ? null : set.disjoin(e))) {
                    e.success.invoke(null);
                    break;
                }
            } else if (STATE.compareAndSet(port, s, s == null ? 1 : ((Integer) s) + 1)) break;
        }
    }

    static IFn acquire(Port port, IFn success, IFn failure) {
        for(;;) {
            Object s = port.state;
            if (s instanceof Integer) {
                int n = (Integer) s;
                if (STATE.compareAndSet(port, s, n == 1 ? null : n - 1)) {
                    success.invoke(null);
                    return NOP;
                }
            } else {
                Event e = new Event(port, success, failure);
                IPersistentSet set = (s == null) ? PersistentHashSet.EMPTY : (IPersistentSet) s;
                if (STATE.compareAndSet(port, s, set.cons(e))) return e;
            }
        }
    }

    static void cancelAcquire(Port port, Event e) {
        for(;;) {
            Object s = port.state;
            if (!(s instanceof IPersistentSet)) break;
            IPersistentSet set = (IPersistentSet) s;
            if (!(set.contains(e))) break;
            if (STATE.compareAndSet(port, s, set.count() == 1 ? null : set.disjoin(e))) {
                e.failure.invoke(new Cancelled("Semaphore acquire cancelled."));
                break;
            }
        }
    }

    static Port make(int permits) {
        return new Port(permits);
    }
}


