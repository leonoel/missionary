package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;

public interface Relieve {

    class Process extends AFn implements IDeref {
        static {
            Util.printDefault(Process.class);
        }

        IFn reducer;
        IFn notifier;
        IFn terminator;
        Object iterator;
        Object current;
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
        Object x;
        IFn n, r;
        synchronized (ps) {
            n = ps.notifier;
            r = ps.reducer;
            x = ps.current;
            ps.current = ps;
        }
        if (r == null) ps.terminator.invoke();
        return n == null ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }

    static IFn ready(Process ps) {
        IFn cb = null;
        while (ps.busy = !ps.busy) if (ps.done) {
            ps.reducer = null;
            if (ps.current == ps) cb = ps.terminator;
        } else {
            IFn n = ps.notifier;
            if (n == null) Util.discard(ps.iterator); else {
                Object r = ps.current;
                try {
                    Object x = ((IDeref) ps.iterator).deref();
                    ps.current = r == ps ? x : ps.reducer.invoke(r, x);
                } catch (Throwable e) {
                    ps.current = e;
                    ps.notifier = null;
                    ((IFn) ps.iterator).invoke();
                }
                if (r == ps) cb = n;
            }
        }
        return cb;
    }

    static Process run(IFn r, IFn f, IFn n, IFn t) {
        IFn cb;
        Process ps = new Process();
        synchronized (ps) {
            ps.busy = true;
            ps.reducer = r;
            ps.notifier = n;
            ps.terminator = t;
            ps.current = ps;
            ps.iterator = f.invoke(
                    new AFn() {
                        @Override
                        public Object invoke() {
                            IFn cb;
                            synchronized (ps) {
                                cb = ready(ps);
                            }
                            if (cb != null) cb.invoke();
                            return null;
                        }},
                    new AFn() {
                        @Override
                        public Object invoke() {
                            ps.done = true;
                            IFn cb;
                            synchronized (ps) {
                                cb = ready(ps);
                            }
                            if (cb != null) cb.invoke();
                            return null;
                        }});
            cb = ready(ps);
        }
        if (cb != null) cb.invoke();
        return ps;
    }

}
