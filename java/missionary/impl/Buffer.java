package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class Buffer extends AFn implements IDeref {

    static final AtomicIntegerFieldUpdater<Buffer> PRESSURE =
            AtomicIntegerFieldUpdater.newUpdater(Buffer.class, "pressure");

    static final AtomicIntegerFieldUpdater<Buffer> AVAILABLE =
            AtomicIntegerFieldUpdater.newUpdater(Buffer.class, "available");

    IFn notifier;
    IFn terminator;
    Object iterator;
    Object[] buffer;
    int failed = -1;
    int push;
    int pull;
    boolean done;

    volatile int pressure;
    volatile int available;

    void more() {
        do if (done) {
            buffer[push] = AVAILABLE;
            if (0 == AVAILABLE.getAndIncrement(this)) terminator.invoke();
            return;
        } else {
            try {
                buffer[push] = ((IDeref) iterator).deref();
            } catch (Throwable e) {
                buffer[failed = push] = e;
            }
            push = (push + 1) % buffer.length;
            int before, after;
            while (!AVAILABLE.compareAndSet(this, before = available, after = before + 1));
            if (before == 0) notifier.invoke();
            if (after == buffer.length) return;
        } while (0 == PRESSURE.decrementAndGet(this));
    }

    public Buffer(int c, IFn f, IFn n, IFn t) {
        buffer = new Object[c];
        notifier = n;
        terminator = t;
        iterator = f.invoke(
                new AFn() {
                    @Override
                    public Object invoke() {
                        if (0 == PRESSURE.incrementAndGet(Buffer.this)) more();
                        return null;
                    }
                },
                new AFn() {
                    @Override
                    public Object invoke() {
                        done = true;
                        if (0 == PRESSURE.incrementAndGet(Buffer.this)) more();
                        return null;
                    }
                });
        if (0 == PRESSURE.decrementAndGet(this)) more();
    }

    @Override
    public Object invoke() {
        return ((IFn) iterator).invoke();
    }

    @Override
    public Object deref() {
        Object x = buffer[pull];
        boolean f = failed == pull;
        buffer[pull] = null;
        pull = (pull + 1) % buffer.length;
        int before, after;
        while (!AVAILABLE.compareAndSet(this, before = available, after = before - 1));
        if (after != 0) (buffer[pull] == AVAILABLE ? terminator : notifier).invoke();
        if (before == buffer.length && 0 == PRESSURE.decrementAndGet(this)) more();
        return f ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }
}
