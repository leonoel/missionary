package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public interface Relieve {

    AtomicReferenceFieldUpdater<Process, Object> CURRENT =
            AtomicReferenceFieldUpdater.newUpdater(Process.class, Object.class, "current");

    class Process extends AFn implements IDeref {
        static {
            Util.printDefault(Process.class);
        }

        IFn reducer;
        IFn notifier;
        IFn terminator;
        Object iterator;
        Throwable error;
        Object last;
        boolean busy;

        volatile Object current = CURRENT;

        @Override
        public Object invoke() {
            return ((IFn) iterator).invoke();
        }

        @Override
        public Object deref() {
            return transfer(this);
        }
    }

    static Object transfer(Process process) {
        for(;;) {
            Object x = process.current;
            if (x == CURRENT) {
                if (process.last == CURRENT) {
                    process.terminator.invoke();
                    return clojure.lang.Util.sneakyThrow(process.error);
                } else {
                    x = process.last;
                    process.last = CURRENT;
                    ((process.error != null) ? process.notifier : process.terminator).invoke();
                    return x;
                }
            }
            if (CURRENT.compareAndSet(process, x, CURRENT)) return x;
        }
    }

    static void pull(Process process) {
        IFn rf;
        Object p;
        IFn signal = Util.NOP;
        Object it = process.iterator;
        while (process.busy = !process.busy) if ((rf = process.reducer) == null) {
            while (!CURRENT.compareAndSet(process, process.last = process.current, CURRENT));
            if (process.last == CURRENT) signal = process.error == null ? process.terminator : process.notifier;
        } else try {
            Object x = ((IDeref) it).deref();
            while (!CURRENT.compareAndSet(process, p = process.current, (p == CURRENT) ? x : rf.invoke(p, x)));
            if (p == CURRENT) signal = process.notifier;
        } catch (Throwable e) {
            process.error = e;
            ((IFn) it).invoke();
        }
        signal.invoke();
    }

    static Process run(IFn r, IFn f, IFn n, IFn t) {
        Process process = new Process();
        synchronized (process) {
            process.busy = true;
            process.reducer = r;
            process.notifier = n;
            process.terminator = t;
            process.iterator = f.invoke(
                    new AFn() {
                        @Override
                        public Object invoke() {
                            synchronized (process) {
                                pull(process);
                                return null;
                            }
                        }},
                    new AFn() {
                        @Override
                        public Object invoke() {
                            synchronized (process) {
                                process.reducer = null;
                                pull(process);
                                return null;
                            }
                        }});
            pull(process);
            return process;
        }
    }

}
