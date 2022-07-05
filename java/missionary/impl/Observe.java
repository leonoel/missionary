package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import missionary.Cancelled;

public interface Observe {

    class Process extends AFn implements IDeref {
        IFn notifier;
        IFn terminator;
        Object unsub;
        Object value;

        @Override
        public Object invoke() {
            kill(this);
            return null;
        }

        @Override
        public Object deref() {
            return transfer(this);
        }
    }

    static void kill(Process ps) {
        IFn cb;
        synchronized (ps) {
            cb = ps.notifier;
            if (cb != null) {
                ps.notifier = null;
                if (ps.value != ps) cb = null;
                try {
                    ((IFn) ps.unsub).invoke();
                    ps.value = new Cancelled("Observe cancelled.");
                } catch (Throwable e) {
                    ps.value = e;
                }
            }
        }
        if (cb != null) cb.invoke();
    }

    static Object transfer(Process ps) {
        IFn n;
        Object x;
        synchronized (ps) {
            n = ps.notifier;
            x = ps.value;
            ps.value = ps;
        }
        if (n == null) {
            ps.terminator.invoke();
            return clojure.lang.Util.sneakyThrow((Throwable) x);
        } else return x;
    }

    static Object run(IFn sub, IFn n, IFn t) {
        Process ps = new Process();
        ps.notifier = n;
        ps.terminator = t;
        ps.value = ps;
        try {
            ps.unsub = sub.invoke(new AFn() {
                @Override
                public Object invoke(Object x) {
                    IFn cb;
                    synchronized (ps) {
                        cb = ps.notifier;
                        if (cb == null) throw new Error("Can't process event - observer is terminated.");
                        if (ps.value != ps) throw new Error("Can't process event - observer is not ready.");
                        ps.value = x;
                    }
                    return cb.invoke();
                }
            });
        } catch (Throwable e) {
            ps.value = e;
            ps.notifier = null;
            n.invoke();
        }
        return ps;
    }
}
