package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public interface Sleep {

    AtomicReferenceFieldUpdater<Scheduler, IPersistentMap> PENDING =
            AtomicReferenceFieldUpdater.newUpdater(Scheduler.class, IPersistentMap.class, "pending");

    final class Scheduler extends Thread {
        static Scheduler INSTANCE = new Scheduler();

        volatile IPersistentMap pending = PersistentTreeMap.EMPTY;

        Scheduler() {
            super("missionary scheduler");
            setDaemon(true);
            start();
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
                        if (slot instanceof Process) trigger((Process) slot);
                        else for (Object x : (APersistentSet) slot) trigger((Process) x);
                    }
                }
            } catch (InterruptedException _) {
                interrupted();
            }
        }
    }

    final class Process extends AFn {
        static {
            Util.printDefault(Process.class);
        }

        Object payload;
        IFn success;
        IFn failure;
        Long time;

        @Override
        public Object invoke() {
            cancel(this);
            return null;
        }
    }

    static void trigger(Process s) {
        s.success.invoke(s.payload);
    }

    static void schedule(Process s) {
        for(;;) {
            IPersistentMap p = Scheduler.INSTANCE.pending;
            Object slot = p.valAt(s.time);
            IPersistentMap n = p.assoc(s.time, slot == null ? s :
                    slot instanceof Process ?
                            PersistentHashSet.create(slot, s) :
                            ((IPersistentSet) slot).cons(s));
            if (PENDING.compareAndSet(Scheduler.INSTANCE, p, n)) {
                if (((IMapEntry) n.iterator().next()).key().equals(s.time)
                        && slot == null) Scheduler.INSTANCE.interrupt();
                break;
            }
        }
    }

    static void cancel(Process s) {
        for(;;) {
            IPersistentMap p = Scheduler.INSTANCE.pending;
            Object item = p.valAt(s.time);
            if (item == null) break;
            IPersistentMap n;
            if (item instanceof Process) {
                if (item == s) n = p.without(s.time); else break;
            } else {
                IPersistentSet ss = ((IPersistentSet) item).disjoin(s);
                if (ss.equals(item)) break; else n = p.assoc(s.time, ss);
            }
            if (PENDING.compareAndSet(Scheduler.INSTANCE, p, n)) {
                s.failure.invoke(new Cancelled("Sleep cancelled."));
                break;
            }
        }
    }

    static Process run(long d, Object x, IFn s, IFn f) {
        Process ps = new Process();
        ps.payload = x;
        ps.success = s;
        ps.failure = f;
        ps.time = System.currentTimeMillis() + d;
        schedule(ps);
        return ps;
    }
}