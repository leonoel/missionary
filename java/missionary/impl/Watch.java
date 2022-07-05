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
            Object x;
            synchronized (ps) {
                x = ps.value;
                ps.value = curr;
            }
            return x == ps ? ps.notifier.invoke() : null;
        }
    };

    static void kill(Process ps) {
        IFn cb;
        synchronized (ps) {
            cb = ps.notifier;
            if (cb != null) {
                ps.notifier = null;
                if (ps.value != ps) cb = null;
                ps.reference.removeWatch(ps);
                ps.value = new Cancelled("Watch cancelled.");
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
