package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public interface Eduction {

    AtomicIntegerFieldUpdater<Process> PRESSURE =
            AtomicIntegerFieldUpdater.newUpdater(Process.class, "pressure");

    IFn FEED = new AFn() {
        @Override
        public Object invoke(Object r) {
            return r;
        }
        @Override
        public Object invoke(Object r, Object x) {
            push((Process) r, x);
            return r;
        }
    };

    final class Process extends AFn implements IDeref {

        static {
            Util.printDefault(Process.class);
        }

        IFn reducer;
        Object iterator;
        IFn notifier;
        IFn terminator;
        Object[] buffer = new Object[1];
        int offset;
        int length;
        int error = -1;
        boolean done;

        volatile int pressure;

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
        ((IFn) ps.iterator).invoke();
    }

    static Object transfer(Process ps) {
        Object x = ps.buffer[ps.offset];
        boolean f = ps.error == ps.offset;
        ps.buffer[ps.offset++] = null;
        if (ps.offset != ps.length) ps.notifier.invoke();
        else if (0 == PRESSURE.decrementAndGet(ps)) pull(ps);
        return f ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }

    static void push(Process ps, Object x) {
        if (ps.length == ps.buffer.length) {
            Object[] bigger = new Object[ps.length << 1];
            System.arraycopy(ps.buffer, 0, bigger, 0, ps.length);
            ps.buffer = bigger;
        }
        ps.buffer[ps.length++] = x;
    }

    static void pull(Process ps) {
        for(;;) if (ps.done) if (ps.reducer == null) {
            ps.terminator.invoke();
            return;
        } else {
            ps.offset = 0;
            ps.length = 0;
            try {
                ps.reducer.invoke(ps);
            } catch (Throwable e) {
                ps.error = ps.length;
                push(ps, e);
            }
            ps.reducer = null;
            if (ps.length != 0) {
                ps.notifier.invoke();
                if (0 != PRESSURE.incrementAndGet(ps)) return;
            }
        } else if (ps.reducer == null) {
            try {
                ((IDeref) ps.iterator).deref();
            } catch (Throwable _) {}
            if (0 != PRESSURE.decrementAndGet(ps)) return;
        } else {
            ps.offset = 0;
            ps.length = 0;
            try {
                if (ps.reducer.invoke(ps, ((IDeref) ps.iterator).deref()) instanceof Reduced) {
                    ps.reducer.invoke(ps);
                    ps.reducer = null;
                    cancel(ps);
                }
            } catch (Throwable e) {
                ps.error = ps.length;
                push(ps, e);
                ps.reducer = null;
                cancel(ps);
            }
            if (ps.length != 0) {
                ps.notifier.invoke();
                return;
            }
            if (0 != PRESSURE.decrementAndGet(ps)) return;
        }
    }

    static Process run(IFn x, IFn f, IFn n, IFn t) {
        Process ps = new Process();
        ps.notifier = n;
        ps.terminator = t;
        ps.reducer = (IFn) x.invoke(FEED);
        ps.iterator = f.invoke(
                new AFn() {
                    @Override
                    public Object invoke() {
                        if (0 == PRESSURE.incrementAndGet(ps)) pull(ps);
                        return null;
                    }
                },
                new AFn() {
                    @Override
                    public Object invoke() {
                        ps.done = true;
                        if (0 == PRESSURE.incrementAndGet(ps)) pull(ps);
                        return null;
                    }
                }
        );
        if (0 == PRESSURE.decrementAndGet(ps)) pull(ps);
        return ps;
    }

}
