package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Util.NOP;

public final class Rendezvous extends AFn implements Event.Emitter {
    static final AtomicReferenceFieldUpdater<Rendezvous, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Rendezvous.class, Object.class, "state");

    volatile Object state = null;

    class Put extends AFn implements Event.Emitter {
        Object value;

        Put(Object x) {
            value = x;
        }

        @Override
        public Object invoke(Object success, Object failure) {
            for(;;) {
                Object s = state;
                if (s instanceof IPersistentSet) {
                    IPersistentSet set = (IPersistentSet) s;
                    Event e = (Event) RT.iter(s).next();
                    if (STATE.compareAndSet(Rendezvous.this, s, set.count() == 1 ? null : set.disjoin(e))) {
                        e.success.invoke(value);
                        ((IFn) success).invoke(null);
                        return NOP;
                    }
                } else {
                    Event e = new Event(this, (IFn) success, (IFn) failure);
                    IPersistentMap map = s == null ? PersistentHashMap.EMPTY : (IPersistentMap) s;
                    if (STATE.compareAndSet(Rendezvous.this, s, map.assoc(e, value))) return e;
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
                if (STATE.compareAndSet(Rendezvous.this, s, set.count() == 1 ? null : set.disjoin(e))) {
                    e.failure.invoke(new ExceptionInfo("Rendez-vous give cancelled.", RT.map(
                            Keyword.intern(null, "cancelled"),
                            Keyword.intern("missionary", "rdv-give"))));
                    break;
                }
            }
        }
    }

    @Override
    public Object invoke(Object x) {
        return new Put(x);
    }

    @Override
    public Object invoke(Object success, Object failure) {
        for(;;) {
            Object s = state;
            if (s instanceof IPersistentMap) {
                IPersistentMap map = (IPersistentMap) s;
                MapEntry e = (MapEntry) RT.iter(s).next();
                if (STATE.compareAndSet(this, s, map.count() == 1 ? null : map.without(e.key()))) {
                    ((Event) e.key()).success.invoke(null);
                    ((IFn) success).invoke(e.val());
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
                e.failure.invoke(new ExceptionInfo("Rendez-vous take cancelled.", RT.map(
                        Keyword.intern(null, "cancelled"),
                        Keyword.intern("missionary", "rdv-take"))));
                break;
            }
        }
    }
}
