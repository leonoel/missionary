package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.Util;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class Relieve extends AFn implements IDeref {

    static final AtomicIntegerFieldUpdater<Relieve> PRESSURE =
            AtomicIntegerFieldUpdater.newUpdater(Relieve.class, "pressure");

    static final AtomicReferenceFieldUpdater<Relieve, Object> CURRENT =
            AtomicReferenceFieldUpdater.newUpdater(Relieve.class, Object.class, "current");

    IFn reducer;
    IFn notifier;
    IFn terminator;
    Object iterator;
    Throwable error;
    Object last;

    volatile int pressure;
    volatile Object current = CURRENT;

    void pull() {
        do if (reducer == null) {
            while (!CURRENT.compareAndSet(this, last = current, CURRENT));
            if (last == CURRENT) ((error == null) ? terminator : notifier).invoke();
        } else try {
            IFn r = reducer;
            Object x = ((IDeref) iterator).deref();
            Object p;
            while (!CURRENT.compareAndSet(this, p = current, (p == CURRENT) ? x : r.invoke(p, x)));
            if (p == CURRENT) notifier.invoke();
        } catch (Throwable e) {
            error = e;
            invoke();
        } while (0 == PRESSURE.decrementAndGet(this));
    }

    public Relieve(IFn r, IFn f, IFn n, IFn t) {
        reducer = r;
        notifier = n;
        terminator = t;
        iterator = f.invoke(
                new AFn() {
                    @Override
                    public Object invoke() {
                        if (0 == PRESSURE.incrementAndGet(Relieve.this)) pull();
                        return null;
                    }},
                new AFn() {
                    @Override
                    public Object invoke() {
                        reducer = null;
                        if (0 == PRESSURE.incrementAndGet(Relieve.this)) pull();
                        return null;
                    }});
        if (0 == PRESSURE.decrementAndGet(this)) pull();
    }

    @Override
    public Object invoke() {
        return ((IFn) iterator).invoke();
    }

    @Override
    public Object deref() {
        for(;;) {
            Object x = current;
            if (x == CURRENT) {
                if (last == CURRENT) {
                    terminator.invoke();
                    return Util.sneakyThrow(error);
                } else {
                    x = last;
                    last = CURRENT;
                    ((error != null) ? notifier : terminator).invoke();
                    return x;
                }
            }
            if (CURRENT.compareAndSet(this, x, CURRENT)) return x;
        }
    }
}
