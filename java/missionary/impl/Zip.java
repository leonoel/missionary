package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.RT;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class Zip extends AFn implements IDeref {

    static final AtomicIntegerFieldUpdater<Zip> PENDING =
            AtomicIntegerFieldUpdater.newUpdater(Zip.class, "pending");

    IFn combine;
    IFn notifier;
    IFn terminator;
    Object[] iterators;
    Object[] buffer;

    volatile int pending;

    IFn flusher = new AFn() {
        @Override
        public Object invoke() {
            int c;
            do {
                c = 0;
                for(Object it : iterators) {
                    if (it != null) try {
                        c++;
                        ((IDeref) it).deref();
                    } catch (Throwable _) {}
                }
                if (c == 0) {
                    terminator.invoke();
                    return null;
                }
            } while (0 == PENDING.addAndGet(Zip.this, c));
            return null;
        }
    };

    public Zip(IFn f, Object fs, IFn n, IFn t) {
        combine = f;
        notifier = n;
        terminator = t;
        int c = RT.count(fs);
        buffer = new Object[c];
        iterators = new Object[c];
        Iterator it = RT.iter(fs);
        int i = 0;
        do {
            int index = i++;
            iterators[index] = ((IFn) it.next()).invoke(
                    new AFn() {
                        @Override
                        public Object invoke() {
                            if (0 == PENDING.decrementAndGet(Zip.this)) notifier.invoke();
                            return null;
                        }
                    },
                    new AFn() {
                        @Override
                        public Object invoke() {
                            iterators[index] = null;
                            notifier = flusher;
                            int p = PENDING.decrementAndGet(Zip.this);
                            if (0 <= p) {
                                Zip.this.invoke();
                                if (p == 0) notifier.invoke();
                            }
                            return null;
                        }
                    }
            );
        } while (it.hasNext());
        if (0 == PENDING.addAndGet(this, c)) notifier.invoke();
    }

    @Override
    public Object invoke() {
        for(Object it: iterators) {
            if (it != null) ((IFn) it).invoke();
        }
        return null;
    }

    @Override
    public Object deref() {
        int c = 0;
        try {
            for (Object it : iterators) {
                int i = c++;
                buffer[i] = ((IDeref) it).deref();
            }
            return Util.apply(combine, buffer);
        } catch (Throwable e) {
            notifier = flusher;
            throw e;
        } finally {
            if (0 == PENDING.addAndGet(this, c)) notifier.invoke();
            if (notifier == flusher) invoke();
        }
    }
}
