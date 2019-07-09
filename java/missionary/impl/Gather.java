package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.RT;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class Gather extends AFn implements IDeref {

    static final AtomicIntegerFieldUpdater<Gather> STATE =
            AtomicIntegerFieldUpdater.newUpdater(Gather.class, "state");

    static final AtomicIntegerFieldUpdater<Gather> ALIVE =
            AtomicIntegerFieldUpdater.newUpdater(Gather.class, "alive");

    IFn notifier;
    IFn terminator;
    int arity;
    Object[] iterators;
    int[] prevs;
    int[] nexts;
    int head = -1;

    volatile int state = -2;
    volatile int alive = 0;

    public Gather(Object fs, IFn n, IFn t) {
        notifier = n;
        terminator = t;
        arity = RT.count(fs);
        iterators = new Object[arity];
        prevs = new int[arity];
        nexts = new int[arity];
        IFn term = new AFn() {
            @Override
            public Object invoke() {
                if (arity == ALIVE.incrementAndGet(Gather.this))
                    terminator.invoke();
                return null;
            }
        };
        Iterator it = RT.iter(fs);
        int i = 0;
        do {
            int index = i++;
            iterators[index] = ((IFn) it.next()).invoke(
                    new AFn() {
                        @Override
                        public Object invoke() {
                            for(;;) {
                                int n = state;
                                if (n == -2) {
                                    if (STATE.compareAndSet(Gather.this, n, -1)) {
                                        nexts[index] = -1;
                                        head = index;
                                        notifier.invoke();
                                        break;
                                    }
                                } else {
                                    prevs[index] = n;
                                    if (STATE.compareAndSet(Gather.this, n, index)) break;
                                }
                            }
                            return null;
                        }
                    }, term);
        } while (it.hasNext());
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
        try {
            return ((IDeref) iterators[head]).deref();
        } catch (Throwable e) {
            notifier = new AFn() {
                @Override
                public Object invoke() {
                    try {deref();} catch (Throwable _) {}
                    return null;
                }
            };
            invoke();
            throw e;
        } finally {
            head = nexts[head];
            if (head != -1) notifier.invoke();
            else if (!STATE.compareAndSet(this, -1, -2)) {
                while (!STATE.compareAndSet(this, head = state, -1));
                nexts[head] = -1;
                int p;
                while((p = prevs[head]) != -1) {
                    nexts[p] = head;
                    head = p;
                }
                notifier.invoke();
            }
        }
    }
}
