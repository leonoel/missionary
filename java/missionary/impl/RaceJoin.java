package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.RT;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public interface RaceJoin {

    AtomicIntegerFieldUpdater<Process> RACE =
            AtomicIntegerFieldUpdater.newUpdater(Process.class, "race");
    AtomicIntegerFieldUpdater<Process> JOIN =
            AtomicIntegerFieldUpdater.newUpdater(Process.class, "join");

    final class Process extends AFn {
        static {
            Util.printDefault(Process.class);
        }

        IFn raceCallback;
        IFn joinCallback;
        IFn combinator;

        Object[] children;
        Object[] result;

        volatile int race = -2;
        volatile int join = 0;

        @Override
        public Object invoke() {
            cancel(this);
            return null;
        }
    }

    static void cancel(Process ps) {
        for(Object c : ps.children) {
            ((IFn) c).invoke();
        }
    }

    static void terminated(Process ps) {
        if (ps.result.length == JOIN.incrementAndGet(ps)) {
            if (ps.race < 0) try {
                ps.joinCallback.invoke(Util.apply(ps.combinator, ps.result));
            } catch (Throwable e) {
                ps.raceCallback.invoke(e);
            } else ps.raceCallback.invoke(ps.result[ps.race]);
        }
    }

    static Process run(boolean r, IFn c, Object tasks, IFn s, IFn f) {
        Process ps = new Process();
        ps.raceCallback = r ? s : f;
        ps.joinCallback = r ? f : s;
        ps.combinator = c;
        Iterator it = RT.iter(tasks);
        int count = RT.count(tasks);
        ps.children = new Object[count];
        ps.result = new Object[count];
        int i = 0;
        do {
            int index = i++;
            IFn joinCallback = new AFn() {
                @Override
                public Object invoke(Object x) {
                    ps.result[index] = x;
                    terminated(ps);
                    return null;
                }
            };
            IFn raceCallback = new AFn() {
                @Override
                public Object invoke(Object x) {
                    for(;;) {
                        int r = ps.race;
                        if (0 <= r) break;
                        if (RACE.compareAndSet(ps, r, index)) {
                            if (r == -1) cancel(ps);
                            break;
                        }
                    }
                    return joinCallback.invoke(x);
                }
            };
            ps.children[index] = ((IFn) it.next()).invoke(
                    r ? raceCallback : joinCallback,
                    r ? joinCallback : raceCallback);
        } while (it.hasNext());
        if (!RACE.compareAndSet(ps, -2, -1)) cancel(ps);
        return ps;
    }

}