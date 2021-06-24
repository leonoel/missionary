package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.RT;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public interface Latest {
    AtomicIntegerFieldUpdater<It> STATE =
            AtomicIntegerFieldUpdater.newUpdater(It.class, "state");

    AtomicIntegerFieldUpdater<It> ALIVE =
            AtomicIntegerFieldUpdater.newUpdater(It.class, "alive");

    class It extends AFn implements IDeref {

        IFn combinator;
        IFn notifier;
        IFn terminator;
        Object[] args;
        Object[] iterators;
        int[] prevs;        // -1: booting, arity: earliest, [0,arity-1]: previous
        volatile int state; // -1: idle, arity: transfering, [0,arity-1]: ready
        volatile int alive;

        @Override
        public Object invoke() {
            for(Object it : iterators) {
                ((IFn) it).invoke();
            }
            return null;
        }

        @Override
        public Object deref() {
            int i = STATE.getAndSet(this, prevs.length);
            int p;
            do {
                p = prevs[i];
                try {
                    args[i] = ((IDeref) iterators[i]).deref();
                } catch (Throwable e) {
                    notifier = null;
                    invoke();
                    if (p == prevs.length) idle(this); else flush(this, p);
                    throw e;
                }
            } while ((i = p) != prevs.length);
            try {
                Object x = Util.apply(combinator, args);
                idle(this);
                return x;
            } catch (Throwable e) {
                notifier = null;
                invoke();
                idle(this);
                throw e;
            }
        }
    }

    static void idle(It it) {
        if (!STATE.compareAndSet(it, it.prevs.length, -1)) {
            if (it.terminator == null) ready(it);
            else it.terminator.invoke();
        }
    }

    static void flush(It it, int i) {
        int p;
        do {
            p = it.prevs[i];
            try {((IDeref) it.iterators[i]).deref();}
            catch (Throwable t) {}
        } while ((i = p) != it.prevs.length);
        idle(it);
    }

    static void ready(It it) {
        if (it.notifier == null) flush(it, STATE.getAndSet(it, it.prevs.length));
        else it.notifier.invoke();
    }

    static It spawn(IFn c, Object fs, IFn n, IFn t) {
        It it = new It();
        it.combinator = c;
        it.notifier = n;
        int arity = RT.count(fs);
        it.args = new Object[arity];
        it.iterators = new Object[arity];
        it.prevs = new int[arity];
        it.state = it.alive = arity;
        IFn term = new AFn() {
            @Override
            public Object invoke() {
                if (0 == ALIVE.decrementAndGet(it)) {
                    it.terminator = t;
                    idle(it);
                }
                return null;
            }
        };
        int i = 0;
        Iterator flows = RT.iter(fs);
        do {
            int index = i++;
            it.prevs[index] = -1;
            it.iterators[index] = ((IFn) flows.next()).invoke(
                    new AFn() {
                        @Override
                        public Object invoke() {
                            int s;
                            if (it.prevs[index] == -1) {
                                it.prevs[index] = index + 1;
                                if (0 == STATE.decrementAndGet(it)) it.notifier.invoke();
                            } else for(;;) if ((s = it.state) == -1) {
                                it.prevs[index] = it.prevs.length;
                                if (STATE.compareAndSet(it, s, index)) {
                                    ready(it);
                                    return null;
                                }
                            } else {
                                it.prevs[index] = s;
                                if (STATE.compareAndSet(it, s, index)) {
                                    return null;
                                }
                            }
                            return null;
                        }
                    }, term);
        } while (flows.hasNext());
        return it;
    }

}