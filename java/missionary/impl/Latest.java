package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.RT;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class Latest extends AFn implements IDeref {
    static final AtomicIntegerFieldUpdater<Latest> STATE =
            AtomicIntegerFieldUpdater.newUpdater(Latest.class, "state");

    static final AtomicIntegerFieldUpdater<Latest> ALIVE =
            AtomicIntegerFieldUpdater.newUpdater(Latest.class, "alive");

    IFn combinator;
    IFn notifier;
    IFn terminator;
    Object[] args;
    Object[] iterators;
    int[] prevs;

    volatile int state;
    volatile int alive;

    public Latest (IFn c, Object fs, IFn n, IFn t) {
        combinator = c;
        notifier = n;
        terminator = t;
        int arity = RT.count(fs);
        args = new Object[arity];
        iterators = new Object[arity];
        prevs = new int[arity];
        state = arity;
        alive = arity;
        IFn term = new AFn() {
            @Override
            public Object invoke() {
                if (0 == ALIVE.decrementAndGet(Latest.this) &&
                        !STATE.compareAndSet(Latest.this, -1, arity))
                    terminator.invoke();
                return null;
            }
        };
        int i = 0;
        Iterator flows = RT.iter(fs);
        do {
            int index = i++;
            prevs[index] = -1;
            iterators[index] = ((IFn) flows.next()).invoke(
                    new AFn() {
                        @Override
                        public Object invoke() {
                            if (prevs[index] == -1) {
                                prevs[index] = index + 1;
                                if (0 == STATE.decrementAndGet(Latest.this)) notifier.invoke();
                            } else for(;;) {
                                prevs[index] = state;
                                if (STATE.compareAndSet(Latest.this, prevs[index], index)) {
                                    if (prevs[index] == arity) notifier.invoke();
                                    break;
                                }
                            }
                            return null;
                        }
                    }, term);
        } while (flows.hasNext());
    }

    @Override
    public Object invoke() {
        for(Object it : iterators) {
            ((IFn) it).invoke();
        }
        return null;
    }

    @Override
    public Object deref() {
        int arity = iterators.length;
        try {
            for(;;) {
                int i = state;
                if (STATE.compareAndSet(this, i, -1)) {
                    do {
                        int n = prevs[i];
                        args[i] = ((IDeref) iterators[i]).deref();
                        i = n;
                    } while (i != arity);
                    return Util.apply(combinator, args);
                }
            }
        } catch (Throwable e) {
            notifier = new AFn() {
                @Override
                public Object invoke() {
                    try {deref();} catch (Throwable e) {}
                    return null;
                }
            };
            invoke();
            throw e;
        } finally {
            if (!STATE.compareAndSet(this, -1, arity)) {
                (state == arity ? terminator : notifier).invoke();
            }
        }
    }
}
