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
        public synchronized Object deref() {
            return transfer(this);
        }
    }

    static Object transfer(Process p) {
        try {
            IFn f = p.reducer;
            Object r = p.result;
            r = r == p ? f.invoke() : f.invoke(r, ((IDeref) p.input).deref());
            if (r instanceof Reduced) {
                ((IFn) p.input).invoke();
                p.reducer = null;
                r = ((Reduced) r).deref();
            }
            return p.result = r;
        } catch (Throwable e) {
            ((IFn) p.input).invoke();
            p.reducer = null;
            throw e;
        } finally {
            ready(p);
        }
    }

    static void ready(Process p) {
        while (p.busy = !p.busy) if (p.done) {
            p.terminator.invoke();
            break;
        } else if (p.reducer == null) try {
            ((IDeref) p.input).deref();
        } catch (Throwable ignored) {
        } else {
            p.notifier.invoke();
            break;
        }
    }

    static Process run(IFn r, IFn f, IFn n, IFn t) {
        Process p = new Process();
        p.busy = true;
        p.result = p;
        p.reducer = r;
        p.notifier = n;
        p.terminator = t;
        p.input = f.invoke(
                new AFn() {
                    @Override
                    public Object invoke() {
                        synchronized (p) {
                            ready(p);
                            return null;
                        }
                    }
                },
                new AFn() {
                    @Override
                    public Object invoke() {
                        synchronized (p) {
                            p.done = true;
                            ready(p);
                            return null;
                        }
                    }});
        n.invoke();
        return p;
    }

}
