package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class Integrate extends AFn implements IDeref {
    static final AtomicIntegerFieldUpdater<Integrate> PRESSURE =
            AtomicIntegerFieldUpdater.newUpdater(Integrate.class, "pressure");

    Object accumulator;
    IFn reducer;
    IFn notifier;
    IFn terminator;
    Object iterator;
    Throwable error;
    boolean done;
    volatile int pressure;

    void more() {
        do if (done) {
            terminator.invoke();
            return;
        } else if (reducer == null) {
            try {((IDeref) iterator).deref();}
            catch (Throwable ignored) {}
        } else {
            try {
                accumulator = reducer.invoke(accumulator, ((IDeref) iterator).deref());
                if (accumulator instanceof Reduced) {
                    reducer = null;
                    accumulator = ((IDeref) accumulator).deref();
                    invoke();
                }
            } catch (Throwable e) {
                reducer = null;
                error = e;
                invoke();
            }
            notifier.invoke();
            return;
        } while(0 == PRESSURE.decrementAndGet(this));
    }

    public Integrate(IFn r, Object i, IFn f, IFn n, IFn t) {
        accumulator = i;
        reducer = r;
        notifier = n;
        terminator = t;
        iterator = f.invoke(
                new AFn() {
                    @Override
                    public Object invoke() {
                        if (0 == PRESSURE.incrementAndGet(Integrate.this)) more();
                        return null;
                    }
                },
                new AFn() {
                    @Override
                    public Object invoke() {
                        done = true;
                        if (0 == PRESSURE.incrementAndGet(Integrate.this)) more();
                        return null;
                    }});
        n.invoke();
    }

    @Override
    public Object invoke() {
        return ((IFn) iterator).invoke();
    }

    @Override
    public Object deref() {
        Object a = accumulator;
        Throwable e = error;
        if (0 == PRESSURE.decrementAndGet(this)) more();
        return (e == null) ? a : clojure.lang.Util.sneakyThrow(e);
    }
}
