package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.Reduced;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class Aggregate extends AFn {
    static final AtomicIntegerFieldUpdater<Aggregate> PRESSURE =
            AtomicIntegerFieldUpdater.newUpdater(Aggregate.class, "pressure");

    IFn reducer;
    IFn status;
    IFn failure;
    Object result;
    Object iterator;
    boolean done;
    volatile int pressure;

    void pull() {
        do {
            if (done) {
                status.invoke(result);
                return;
            } else if (reducer == null) {
                try {((IDeref) iterator).deref();}
                catch (Throwable ignored) {}
            } else try {
                result = reducer.invoke(result, ((IDeref) iterator).deref());
                if (result instanceof Reduced) {
                    ((IFn) iterator).invoke();
                    reducer = null;
                    result = ((Reduced) result).deref();
                }
            } catch (Throwable e) {
                invoke();
                reducer = null;
                status = failure;
                result = e;
            }
        } while (0 == PRESSURE.decrementAndGet(this));
    }

    public Aggregate(IFn r, Object i, IFn f, IFn sc, IFn fc) {
        result = i;
        reducer = r;
        status = sc;
        failure = fc;
        iterator = f.invoke(
                new AFn() {
                    @Override
                    public Object invoke() {
                        if (0 == PRESSURE.incrementAndGet(Aggregate.this)) pull();
                        return null;
                    }
                },
                new AFn() {
                    @Override
                    public Object invoke() {
                        done = true;
                        if (0 == PRESSURE.incrementAndGet(Aggregate.this)) pull();
                        return null;
                    }
                }
        );
        if (0 == PRESSURE.decrementAndGet(this)) pull();
    }

    @Override
    public Object invoke() {
        return ((IFn) iterator).invoke();
    }
}
