package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.RT;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.Arrays;
import java.util.Objects;

public interface Zip {

    AtomicIntegerFieldUpdater<Process> PENDING =
            AtomicIntegerFieldUpdater.newUpdater(Process.class, "pending");

    final class Process extends AFn implements IDeref {

        static {
            Util.printDefault(Process.class);
        }

        IFn combine;
        IFn notifier;
        IFn terminator;
        Object[] iterators;
        Object[] buffer;
        IFn flusher;
        volatile int pending;

        @Override
        public Object invoke() {
            cancel(this);
            return null;
        }

        @Override
        public Object deref() {
            return transfer(this);
        }
    }

    static void cancel(Process ps) {
        for(Object it: ps.iterators) {
            if (it != null) ((IFn) it).invoke();
        }
    }

    static Object transfer(Process ps) {
        int c = 0;
        try {
            for (Object it : ps.iterators) {
                int i = c++;
                ps.buffer[i] = ((IDeref) it).deref();
            }
            return Util.apply(ps.combine, ps.buffer);
        } catch (Throwable e) {
            ps.notifier = ps.flusher;
            throw e;
        } finally {
            if (0 == PENDING.addAndGet(ps, c)) ps.notifier.invoke();
            if (ps.notifier == ps.flusher) cancel(ps);
        }
    }

    static Process run(IFn f, Object fs, IFn n, IFn t) {
        Process ps = new Process();
        ps.combine = f;
        ps.notifier = n;
        ps.terminator = t;
        int c = RT.count(fs);
        ps.buffer = new Object[c];
        ps.iterators = new Object[c];
        ps.flusher = new AFn() {
            @Override
            public Object invoke() {
                int c;
                // if a subprocess terminated cancel the rest
                if (Arrays.stream(ps.iterators).anyMatch(Objects::isNull))
                    Arrays.stream(ps.iterators).filter(Objects::nonNull).forEach(it -> ((IFn) it).invoke());
                do {
                    c = 0;
                    for(Object it : ps.iterators) {
                        if (it != null) try {
                            c++;
                            ((IDeref) it).deref();
                        } catch (Throwable _) {}
                    }
                    if (c == 0) {
                        ps.terminator.invoke();
                        return null;
                    }
                } while (0 == PENDING.addAndGet(ps, c));
                return null;
            }
        };
        Iterator it = RT.iter(fs);
        int i = 0;
        do {
            int index = i++;
            ps.iterators[index] = ((IFn) it.next()).invoke(
                    new AFn() {
                        @Override
                        public Object invoke() {
                            if (0 == PENDING.decrementAndGet(ps)) ps.notifier.invoke();
                            return null;
                        }
                    },
                    new AFn() {
                        @Override
                        public Object invoke() {
                            ps.iterators[index] = null;
                            ps.notifier = ps.flusher;
                            int p = PENDING.decrementAndGet(ps);
                            if (0 <= p) {
                                cancel(ps);
                                if (p == 0) ps.notifier.invoke();
                            }
                            return null;
                        }
                    }
            );
        } while (it.hasNext());
        if (0 == PENDING.addAndGet(ps, c)) ps.notifier.invoke();
        return ps;
    }

}
