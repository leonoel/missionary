package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class Transform extends AFn implements IDeref {
    static final AtomicIntegerFieldUpdater<Transform> PRESSURE =
            AtomicIntegerFieldUpdater.newUpdater(Transform.class, "pressure");

    static final IFn FEED = new AFn() {
        @Override
        public Object invoke(Object r) {
            return r;
        }
        @Override
        public Object invoke(Object r, Object x) {
            ((Transform) r).push(x);
            return r;
        }
    };

    IFn reducer;
    Object iterator;
    IFn notifier;
    IFn terminator;
    Object[] buffer = new Object[1];
    int offset;
    int length;
    int error = -1;
    boolean done;

    volatile int pressure;

    void push(Object x) {
        if (length == buffer.length) {
            Object[] bigger = new Object[length << 1];
            System.arraycopy(buffer, 0, bigger, 0, length);
            buffer = bigger;
        }
        buffer[length++] = x;
    }

    void pull() {
        for(;;) if (done) {
            if (reducer == null) {
                terminator.invoke();
                return;
            } else {
                try {
                    reducer.invoke(this);
                } catch (Throwable e) {
                    error = length;
                    push(e);
                }
                reducer = null;
                if (length != 0) {
                    notifier.invoke();
                    if (0 != PRESSURE.incrementAndGet(this)) return;
                }
            }
        } else {
            if (reducer == null) {
                try {
                    ((IDeref) iterator).deref();
                } catch (Throwable _) {}
                if (0 != PRESSURE.decrementAndGet(this)) return;
            } else {
                offset = 0;
                length = 0;
                try {
                    if (reducer.invoke(this, ((IDeref) iterator).deref()) instanceof Reduced) {
                        reducer.invoke(this);
                        reducer = null;
                        invoke();
                    }
                } catch (Throwable e) {
                    error = length;
                    push(e);
                    reducer = null;
                    invoke();
                }
                if (length != 0) {
                    notifier.invoke();
                    return;
                }
                if (0 != PRESSURE.decrementAndGet(this)) return;
            }
        }
    }

    public Transform (IFn x, IFn f, IFn n, IFn t) {
        notifier = n;
        terminator = t;
        reducer = (IFn) x.invoke(FEED);
        iterator = f.invoke(
                new AFn() {
                    @Override
                    public Object invoke() {
                        if (0 == PRESSURE.incrementAndGet(Transform.this)) pull();
                        return null;
                    }
                },
                new AFn() {
                    @Override
                    public Object invoke() {
                        done = true;
                        if (0 == PRESSURE.incrementAndGet(Transform.this)) pull();
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

    @Override
    public Object deref() {
        Object x = buffer[offset];
        boolean f = error == offset;
        buffer[offset++] = null;
        if (offset != length) notifier.invoke();
        else if (0 == PRESSURE.decrementAndGet(this)) pull();
        return f ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }
}
