package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class Sleep extends AFn {

    static final class Scheduler extends Thread {
        static final Scheduler INSTANCE = new Scheduler();

        static final AtomicReferenceFieldUpdater<Scheduler, IPersistentMap> PENDING =
                AtomicReferenceFieldUpdater.newUpdater(Scheduler.class, IPersistentMap.class, "pending");

        volatile IPersistentMap pending = PersistentTreeMap.EMPTY;

        Scheduler() {
            super("missionary scheduler");
            setDaemon(true);
            start();
        }

        void schedule(Sleep s) {
            for(;;) {
                IPersistentMap p = pending;
                Object slot = p.valAt(s.time);
                IPersistentMap n = p.assoc(s.time, slot == null ? s :
                        slot instanceof Sleep ?
                                PersistentHashSet.create(slot, s) :
                                ((IPersistentSet) slot).cons(s));
                if (PENDING.compareAndSet(this, p, n)) {
                    if (((IMapEntry) n.iterator().next()).key().equals(s.time)
                            && slot == null) interrupt();
                    break;
                }
            }
        }

        void cancel(Sleep s) {
            for(;;) {
                IPersistentMap p = pending;
                Object item = p.valAt(s.time);
                if (item == null) break;
                IPersistentMap n;
                if (item instanceof Sleep) {
                    if (item == s) n = p.without(s.time); else break;
                } else {
                    IPersistentSet ss = ((IPersistentSet) item).disjoin(s);
                    if (ss.equals(item)) break; else n = p.assoc(s.time, ss);
                }
                if (PENDING.compareAndSet(this, p, n)) {
                    s.failure.invoke(new Cancelled("Sleep cancelled."));
                    break;
                }
            }
        }

        void trigger(Sleep s) {
            s.success.invoke(s.payload);
        }

        @Override
        public void run() {
            for(;;) try {
                IPersistentMap p = pending;
                if (p.count() == 0) sleep(Long.MAX_VALUE);
                else {
                    IMapEntry e = (IMapEntry) p.iterator().next();
                    long delay = ((Long) e.key()) - System.currentTimeMillis();
                    if (0 < delay) sleep(delay);
                    else if (PENDING.compareAndSet(this, p, p.without(e.key()))) {
                        Object slot = e.val();
                        if (slot instanceof Sleep) trigger((Sleep) slot);
                        else for (Object x : (APersistentSet) slot) trigger((Sleep) x);
                    }
                }
            } catch (InterruptedException _) {
                interrupted();
            }
        }
    }

    Object payload;
    IFn success;
    IFn failure;
    Long time;

    public Sleep(long d, Object x, IFn s, IFn f) {
        payload = x;
        success = s;
        failure = f;
        time = System.currentTimeMillis() + d;
        Scheduler.INSTANCE.schedule(this);
    }

    @Override
    public Object invoke() {
        Scheduler.INSTANCE.cancel(this);
        return null;
    }
}