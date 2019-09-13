package missionary.impl;


import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.PersistentHashMap;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class Sample extends AFn implements IDeref {

    static final AtomicIntegerFieldUpdater<Sample> STATE =
            AtomicIntegerFieldUpdater.newUpdater(Sample.class, "state");

    static final int SAMPLED_BUSY = 1 << 0;
    static final int SAMPLER_BUSY = 1 << 1;
    static final int SAMPLED_DONE = 1 << 2;
    static final int SAMPLER_DONE = 1 << 3;

    static boolean is(int s, int m) {
        return (s & m) == m;
    }

    IFn combinator;
    IFn notifier;
    IFn terminator;

    Object sampledIterator;
    Object samplerIterator;
    Object latest = STATE;

    volatile int state = SAMPLED_BUSY | SAMPLER_BUSY;

    void flush() {
        int s;
        do {
            try {((IDeref) sampledIterator).deref();}
            catch (Throwable _) {}
            while (!STATE.compareAndSet(this, s = state, s ^ SAMPLED_BUSY));
        } while (is(s, SAMPLED_BUSY));
    }

    public Sample(IFn c, IFn sd, IFn sr, IFn n, IFn t) {
        combinator = c;
        notifier = n;
        terminator = t;
        sampledIterator = sd.invoke(new AFn() {
            @Override
            public Object invoke() {
                int s;
                while (!STATE.compareAndSet(Sample.this, s = state, s ^ SAMPLED_BUSY));
                if (is(s, SAMPLED_BUSY | SAMPLER_DONE | SAMPLER_BUSY)) flush();
                return null;
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                int s;
                while (!STATE.compareAndSet(Sample.this, s = state, s | SAMPLED_DONE));
                if (is(s,    SAMPLER_DONE | SAMPLER_BUSY)) terminator.invoke();
                return null;
            }
        });
        samplerIterator = sr.invoke(new AFn() {
            @Override
            public Object invoke() {
                int s;
                while (!STATE.compareAndSet(Sample.this, s = state, s ^ SAMPLER_BUSY));
                if (is(s, SAMPLER_BUSY)) notifier.invoke();
                return null;
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                ((IFn) sampledIterator).invoke();
                int s;
                while (!STATE.compareAndSet(Sample.this, s = state, s | SAMPLER_DONE));
                if (is(s, SAMPLER_BUSY)) {
                    if (is(s, SAMPLED_DONE)) terminator.invoke();
                    else if (!is(s, SAMPLED_BUSY)) flush();
                }
                return null;
            }
        });
    }

    @Override
    public Object invoke() {
        ((IFn) sampledIterator).invoke();
        ((IFn) samplerIterator).invoke();
        return null;
    }

    @Override
    public Object deref() {
        int s = state;
        int m = SAMPLER_BUSY;
        try {
            if (!is(s, SAMPLED_BUSY)) {
                m |= SAMPLED_BUSY;
                latest = ((IDeref) sampledIterator).deref();
            }
            Object x = ((IDeref) samplerIterator).deref();
            if (latest == STATE) throw new IllegalStateException("Unable to sample : flow is not ready.");
            return combinator.invoke(latest, x);
        } catch (Throwable e) {
            latest = null;
            combinator = PersistentHashMap.EMPTY;
            notifier = new AFn() {
                @Override
                public Object invoke() {
                    try { deref(); } catch (Throwable _) {}
                    return null;
                }
            };
            invoke();
            throw e;
        } finally {
            while (!STATE.compareAndSet(this, s, s ^= m)) s = state;
            if (is(s, SAMPLER_BUSY)) {
                if (is(s, SAMPLER_DONE)) {
                    if (is(s, SAMPLED_DONE)) terminator.invoke();
                    else if (!is(s, SAMPLED_BUSY)) flush();
                }
            } else notifier.invoke();
        }
    }
}