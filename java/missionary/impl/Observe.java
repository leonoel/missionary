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
                if (ps.value != ps) cb = null;
                ps.value = ps.notifier = null;
            }
        }
        if (cb != null) cb.invoke();
    }

    static Object transfer(Process ps) {
        if (ps.notifier == null) {
            ps.terminator.invoke();
            ((IFn) ps.unsub).invoke();
            return clojure.lang.Util.sneakyThrow(new Cancelled("Observe cancelled."));
        } else synchronized (ps) {
            Object x = ps.value;
            ps.value = ps;
            return x;
        }
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
                        if (cb != null) {
                            if (ps.value != ps) throw new Error("Can't process event - observer is not ready.");
                            ps.value = x;
                        }
                    }
                    return cb == null ? null : cb.invoke();
                }
            });
        } catch (Throwable e) {
            ps.value = null;
            ps.notifier = null;
            ps.unsub = new AFn() {
                @Override
                public Object invoke() {
                    return clojure.lang.Util.sneakyThrow(e);
                }
            };
            n.invoke();
        }
        return ps;
    }
}
