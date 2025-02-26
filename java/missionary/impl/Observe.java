package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import missionary.Cancelled;

public interface Observe {

    class Process extends AFn implements IDeref {

        static {
            Util.printDefault(Process.class);
        }

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
                try {
                    ((IFn) ps.unsub).invoke();
                    ps.unsub = new Cancelled("Observe cancelled.");
                } catch (Throwable e) {
                    ps.unsub = e;
                }
                if (ps.value != ps) {
                    ps.value = ps;
                    ps.notifyAll();
                    cb = null;
                }
            }
        }
        if (cb != null) cb.invoke();
    }

    static Object transfer(Process ps) {
        if (ps.notifier == null) {
            ps.terminator.invoke();
            return clojure.lang.Util.sneakyThrow((Throwable) ps.unsub);
        } else synchronized (ps) {
            Object x = ps.value;
            ps.value = ps;
            ps.notify();
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
                        while (ps.value != ps) try {
                            ps.wait();
                        } catch (InterruptedException e) {
                            clojure.lang.Util.sneakyThrow(e);
                        }
                        cb = ps.notifier;
                        if (cb != null) ps.value = x;
                    }
                    return cb == null ? null : cb.invoke();
                }
            });
        } catch (Throwable e) {
            IFn cb = n;
            synchronized (ps) {
                ps.unsub = e;
                ps.notifier = null;
                if (ps.value != ps) {
                    ps.value = ps;
                    cb = null;
                }
            }
            if (cb != null) cb.invoke();
        }
        return ps;
    }
}
