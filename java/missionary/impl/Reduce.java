package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.Reduced;

public interface Reduce {

    class Process extends AFn {
        static {
            Util.printDefault(Process.class);
        }

        IFn reducer;
        IFn status;
        IFn failure;
        Object result;
        Object input;
        boolean done;
        boolean busy;

        @Override
        public Object invoke() {
            return ((IFn) input).invoke();
        }
    }

    static void transfer(Process p) {
        IFn f = p.reducer;
        Object r = p.result;
        try {
            r = r == p ? f.invoke() : f.invoke(r, ((IDeref) p.input).deref());
            if (r instanceof Reduced) {
                ((IFn) p.input).invoke();
                p.reducer = null;
                r = ((Reduced) r).deref();
            }
        } catch (Throwable e) {
            ((IFn) p.input).invoke();
            p.reducer = null;
            p.status = p.failure;
            r = e;
        }
        p.result = r;
    }

    static void ready(Process p) {
        while (p.busy = !p.busy) if (p.done) {
            p.status.invoke(p.result);
            break;
        } else if (p.reducer == null) try {
            ((IDeref) p.input).deref();
        } catch (Throwable e) {
        } else transfer(p);
    }

    static Process run(IFn r, IFn i, IFn s, IFn f) {
        Process p = new Process();
        p.busy = true;
        p.result = p;
        p.status = s;
        p.failure = f;
        p.reducer = r;
        p.input = i.invoke(new AFn() {
            @Override
            public Object invoke() {
                synchronized (p) {
                    ready(p);
                    return null;
                }
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                synchronized (p) {
                    p.done = true;
                    ready(p);
                    return null;
                }
            }
        });
        synchronized (p) {
            transfer(p);
            ready(p);
        }
        return p;
    }

}
