package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public interface Relieve {

    AtomicIntegerFieldUpdater<It> PRESSURE =
            AtomicIntegerFieldUpdater.newUpdater(It.class, "pressure");

    AtomicReferenceFieldUpdater<It, Object> CURRENT =
            AtomicReferenceFieldUpdater.newUpdater(It.class, Object.class, "current");

    class It extends AFn implements IDeref {

        IFn reducer;
        IFn notifier;
        IFn terminator;
        Object iterator;
        Throwable error;
        Object last;

        volatile int pressure;
        volatile Object current = CURRENT;

        @Override
        public Object invoke() {
            return cancel(this);
        }

        @Override
        public Object deref() {
            return transfer(this);
        }
    }

    static Object cancel(It it) {
        return ((IFn) it.iterator).invoke();
    }

    static Object transfer(It it) {
        for(;;) {
            Object x = it.current;
            if (x == CURRENT) {
                if (it.last == CURRENT) {
                    it.terminator.invoke();
                    return clojure.lang.Util.sneakyThrow(it.error);
                } else {
                    x = it.last;
                    it.last = CURRENT;
                    ((it.error != null) ? it.notifier : it.terminator).invoke();
                    return x;
                }
            }
            if (CURRENT.compareAndSet(it, x, CURRENT)) return x;
        }
    }

    static void pull(It it) {
        IFn rf;
        Object p;
        IFn signal = Util.NOP;
        do if ((rf = it.reducer) == null) {
            while (!CURRENT.compareAndSet(it, it.last = it.current, CURRENT));
            if (it.last == CURRENT) signal = it.error == null ? it.terminator : it.notifier;
        } else try {
            Object x = ((IDeref) it.iterator).deref();
            while (!CURRENT.compareAndSet(it, p = it.current, (p == CURRENT) ? x : rf.invoke(p, x)));
            if (p == CURRENT) signal = it.notifier;
        } catch (Throwable e) {
            it.error = e;
            it.invoke();
        } while (0 == PRESSURE.decrementAndGet(it));
        signal.invoke();
    }

    static It spawn(IFn r, IFn f, IFn n, IFn t) {
        It it = new It();
        it.reducer = r;
        it.notifier = n;
        it.terminator = t;
        it.iterator = f.invoke(
                new AFn() {
                    @Override
                    public Object invoke() {
                        if (0 == PRESSURE.incrementAndGet(it)) pull(it);
                        return null;
                    }},
                new AFn() {
                    @Override
                    public Object invoke() {
                        it.reducer = null;
                        if (0 == PRESSURE.incrementAndGet(it)) pull(it);
                        return null;
                    }});
        if (0 == PRESSURE.decrementAndGet(it)) pull(it);
        return it;
    }

}
