package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Util.NOP;

public interface Rendezvous {

    AtomicReferenceFieldUpdater<Port, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Port.class, Object.class, "state");

    final class Port extends AFn implements Event.Emitter {
        static {
            Util.printDefault(Port.class);
        }

        volatile Object state = null;

        @Override
        public Object invoke(Object x) {
            return new Give(this, x);
        }

        @Override
        public Object invoke(Object s, Object f) {
            return take(this, (IFn) s, (IFn) f);
        }

        @Override
        public void cancel(Event e) {
            cancelTake(this, e);
        }
    }

    final class Give extends AFn implements Event.Emitter {
        static {
            Util.printDefault(Give.class);
        }

        final Port port;
        final Object value;

        Give(Port p, Object x) {
            port = p;
            value = x;
        }

        @Override
        public Object invoke(Object s, Object f) {
            return give(this, (IFn) s, (IFn) f);
        }

        @Override
        public void cancel(Event e) {
            cancelGive(this, e);
        }
    }

    static void cancelTake(Port port, Event event) {
        for(;;) {
            Object s = port.state;
            if (!(s instanceof IPersistentSet)) break;
            IPersistentSet set = (IPersistentSet) s;
            if (!(set.contains(event))) break;
            if (STATE.compareAndSet(port, s, set.count() == 1 ? null : set.disjoin(event))) {
                event.failure.invoke(new Cancelled("Rendez-vous take cancelled."));
                break;
            }
        }
    }

    static IFn take(Port port, IFn success, IFn failure) {
        for(;;) {
            Object s = port.state;
            if (s instanceof IPersistentMap) {
                IPersistentMap map = (IPersistentMap) s;
                MapEntry e = (MapEntry) RT.iter(s).next();
                if (STATE.compareAndSet(port, s, map.count() == 1 ? null : map.without(e.key()))) {
                    ((Event) e.key()).success.invoke(null);
                    success.invoke(e.val());
                    return NOP;
                }
            } else {
                Event e = new Event(port, success, failure);
                IPersistentSet set = (s == null) ? PersistentHashSet.EMPTY : (IPersistentSet) s;
                if (STATE.compareAndSet(port, s, set.cons(e))) return e;
            }
        }
    }

    static void cancelGive(Give g, Event e) {
        Port port = g.port;
        for(;;) {
            Object s = port.state;
            if (!(s instanceof IPersistentMap)) break;
            IPersistentMap map = (IPersistentMap) s;
            if (!(map.containsKey(e))) break;
            if (STATE.compareAndSet(port, s, map.count() == 1 ? null : map.without(e))) {
                e.failure.invoke(new Cancelled("Rendez-vous give cancelled."));
                break;
            }
        }
    }

    static IFn give(Give g, IFn success, IFn failure) {
        Port port = g.port;
        Object value = g.value;
        for(;;) {
            Object s = port.state;
            if (s instanceof IPersistentSet) {
                IPersistentSet set = (IPersistentSet) s;
                Event e = (Event) RT.iter(s).next();
                if (STATE.compareAndSet(port, s, set.count() == 1 ? null : set.disjoin(e))) {
                    e.success.invoke(value);
                    success.invoke(null);
                    return NOP;
                }
            } else {
                Event e = new Event(g, success, failure);
                IPersistentMap map = s == null ? PersistentHashMap.EMPTY : (IPersistentMap) s;
                if (STATE.compareAndSet(port, s, map.assoc(e, value))) return e;
            }
        }
    }

    static Port make() {
        return new Port();
    }
}