package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;

public interface Buffer {

    class Flow extends AFn {
        final int capacity;
        final IFn input;

        Flow(int c, IFn i) {
            capacity = c;
            input = i;
        }

        @Override
        public Object invoke(Object n, Object t) {
            Process ps = new Process();
            ps.busy = true;
            ps.failed = -1;
            ps.buffer = new Object[capacity];
            ps.notifier = (IFn) n;
            ps.terminator = (IFn) t;
            ps.iterator = input.invoke(
                    new AFn() {
                        @Override
                        public Object invoke() {
                            IFn cb;
                            synchronized (ps) {
                                cb = more(ps);
                            }
                            return cb == null ? null : cb.invoke();
                        }
                    },
                    new AFn() {
                        @Override
                        public Object invoke() {
                            IFn cb;
                            synchronized (ps) {
                                ps.done = true;
                                cb = more(ps);
                            }
                            return cb == null ? null : cb.invoke();
                        }
                    });
            IFn cb;
            synchronized (ps) {
                cb = more(ps);
            }
            if (cb != null) cb.invoke();
            return ps;
        }
    }

    class Process extends AFn implements IDeref {

        static {
            Util.printDefault(Process.class);
        }

        IFn notifier;
        IFn terminator;
        Object iterator;
        Object[] buffer;
        int failed;
        int size;
        int push;
        int pull;
        boolean busy;
        boolean done;

        @Override
        public Object invoke() {
            return ((IFn) iterator).invoke();
        }

        @Override
        public Object deref() {
            return transfer(this);
        }

    }

    static Object transfer(Process ps) {
        Object[] buffer = ps.buffer;
        boolean f;
        Object x;
        IFn cb;
        synchronized (ps) {
            int i = ps.pull;
            int s = ps.size;
            int n = (i + 1) % buffer.length;
            f = ps.failed == i;
            x = buffer[i];
            buffer[i] = null;
            ps.pull = n;
            ps.size = s - 1;
            cb = s == buffer.length ? more(ps) : null;
            cb = s == 1 ? cb : buffer[n] == ps ? ps.terminator : ps.notifier;
        }
        if (cb != null) cb.invoke();
        return f ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }

    static IFn more(Process ps) {
        Object[] buffer = ps.buffer;
        IFn cb = null;
        for(;;) if (ps.busy = !ps.busy) {
            int i = ps.push;
            int s = ps.size;
            ps.push = (i + 1) % buffer.length;
            cb = s == 0 ? ps.done ? ps.terminator : ps.notifier : cb;
            if (ps.done) buffer[i] = ps; else try {
                buffer[i] = ((IDeref) ps.iterator).deref();
            } catch (Throwable e) {
                ps.failed = i;
                buffer[i] = e;
            }
            if ((ps.size = s + 1) == buffer.length) break;
        } else break;
        return cb;
    }

    static Flow flow(int capacity, IFn input) {
        return new Flow(capacity, input);
    }
}
