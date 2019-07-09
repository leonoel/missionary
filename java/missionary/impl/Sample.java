package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class Sample extends AFn implements IDeref {

    static final AtomicIntegerFieldUpdater<Sample> STATE =
            AtomicIntegerFieldUpdater.newUpdater(Sample.class, "state");

    static final int SAMPLED_BUSY = 1 << 0;
    static final int SAMPLER_BUSY = 1 << 1;
    static final int SAMPLED_DONE = 1 << 2;
    static final int SAMPLER_DONE = 1 << 3;

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
            while (STATE.compareAndSet(this, s = state, s ^ SAMPLED_BUSY));
        } while ((s & SAMPLED_BUSY) == 0);
    }

    public Sample(IFn c, IFn sd, IFn sr, IFn n, IFn t) {
        combinator = c;
        notifier = n;
        terminator = t;
        sampledIterator = sd.invoke(new AFn() {
            @Override
            public Object invoke() {
                int s;
                if (latest == STATE) {
                    latest = null;
                    while (!STATE.compareAndSet(Sample.this, s = state, s ^ SAMPLER_BUSY));
                    if ((s & SAMPLER_BUSY) == 0) notifier.invoke();
                } else {
                    while (!STATE.compareAndSet(Sample.this, s = state, s ^ SAMPLED_BUSY));
                    if ((s & SAMPLED_BUSY) == 0 && (s & SAMPLER_DONE) != 0 && (s & SAMPLER_BUSY) == 0)
                        flush();
                }
                return null;
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                int s;
                while (!STATE.compareAndSet(Sample.this, s = state, s | SAMPLED_DONE));
                if ((s & SAMPLER_DONE) != 0 && (s & SAMPLED_BUSY) == 0) terminator.invoke();
                return null;
            }
        });
        samplerIterator = sr.invoke(new AFn() {
            @Override
            public Object invoke() {
                int s;
                while (!STATE.compareAndSet(Sample.this, s = state, s ^ SAMPLER_BUSY));
                if ((s & SAMPLER_BUSY) == 0) notifier.invoke();
                return null;
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                ((IFn) sampledIterator).invoke();
                int s;
                while (!STATE.compareAndSet(Sample.this, s = state, s | SAMPLER_DONE));
                if ((s & SAMPLER_BUSY) == 0 && (s & SAMPLED_DONE) != 0 && (s & SAMPLED_BUSY) == 0)
                    terminator.invoke();
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
        int flip = SAMPLER_BUSY;
        try {
            if ((s & SAMPLED_BUSY) != 0) {
                flip |= SAMPLED_BUSY;
                latest = ((IDeref) sampledIterator).deref();
            }
            return combinator.invoke(latest, ((IDeref) samplerIterator).deref());
        } catch (Throwable e) {
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
            while (!STATE.compareAndSet(this, s, s ^ flip)) s = state;
            if ((s & SAMPLER_BUSY) == 0) notifier.invoke();
            else if ((s & SAMPLER_DONE) != 0) {
                if ((s & SAMPLED_DONE) != 0) terminator.invoke();
                else if ((s & SAMPLED_BUSY) != 0) flush();
            }
        }
    }
}