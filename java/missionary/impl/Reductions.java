package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.Reduced;

public interface Reductions {

    class Process extends AFn implements IDeref {
        static {
            Util.printDefault(Process.class);
        }

        Object result;
        IFn reducer;
        IFn notifier;
        IFn terminator;
        Object input;
        boolean done;
        boolean busy;

        @Override
        public Object invoke() {
            return ((IFn) input).invoke();
        }

        @Override
        public Object deref() {
            return transfer(this);
        }
    }

    static Object transfer(Process ps) {
        IFn cb;
        synchronized (ps) {
            try {
                IFn f = ps.reducer;
                Object r = ps.result;
                r = r == ps ? f.invoke() : f.invoke(r, ((IDeref) ps.input).deref());
                if (r instanceof Reduced) {
                    ((IFn) ps.input).invoke();
                    ps.reducer = null;
                    r = ((Reduced) r).deref();
                }
                ps.result = r;
            } catch (Throwable e) {
                ((IFn) ps.input).invoke();
                ps.notifier = null;
                ps.reducer = null;
                ps.result = e;
            }
            cb = ready(ps);
        }
        if (cb != null) cb.invoke();
        return ps.notifier == null ? clojure.lang.Util.sneakyThrow((Throwable) ps.result) : ps.result;
    }

    static IFn ready(Process ps) {
        IFn cb = null;
        while (ps.busy = !ps.busy) if (ps.done) {
            cb = ps.terminator;
            break;
        } else if (ps.reducer == null) try {
            ((IDeref) ps.input).deref();
        } catch (Throwable ignored) {
        } else {
            cb = ps.notifier;
            break;
        }
        return cb;
    }

    static Process run(IFn r, IFn f, IFn n, IFn t) {
        Process ps = new Process();
        ps.busy = true;
        ps.result = ps;
        ps.reducer = r;
        ps.notifier = n;
        ps.terminator = t;
        ps.input = f.invoke(
                new AFn() {
                    @Override
                    public Object invoke() {
                        IFn cb;
                        synchronized (ps) {
                            cb = ready(ps);
                        }
                        return cb == null ? null : cb.invoke();
                    }
                },
                new AFn() {
                    @Override
                    public Object invoke() {
                        ps.done = true;
                        IFn cb;
                        synchronized (ps) {
                            cb = ready(ps);
                        }
                        return cb == null ? null : cb.invoke();
                    }});
        n.invoke();
        return ps;
    }

}
