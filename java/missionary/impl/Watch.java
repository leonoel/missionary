package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.IRef;
import clojure.lang.IDeref;
import missionary.Cancelled;

public interface Watch {

    class Process extends AFn implements IDeref {
        IFn notifier;
        IFn terminator;
        IRef reference;
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

    IFn watch = new AFn() {
        @Override
        public Object invoke(Object key, Object ref, Object prev, Object curr) {
            Process ps = (Process) key;
            IFn cb;
            synchronized (ps) {
                cb = ps.notifier;
                if (cb != null) {
                    if (ps.value != ps) cb = null;
                    ps.value = curr;
                }
            }
            return cb == null ? null : cb.invoke();
        }
    };

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
            ps.reference.removeWatch(ps);
            return clojure.lang.Util.sneakyThrow(new Cancelled("Watch cancelled."));
        } else synchronized (ps) {
            Object x = ps.value;
            ps.value = ps;
            return x;
        }
    }

    static Object run(IRef r, IFn n, IFn t) {
        Process ps = new Process();
        ps.notifier = n;
        ps.terminator = t;
        ps.reference = r;
        ps.value = r.deref();
        r.addWatch(ps, watch);
        n.invoke();
        return ps;
    }
}
