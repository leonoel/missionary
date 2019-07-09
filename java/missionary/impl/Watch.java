package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.IRef;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class Watch extends AFn implements IDeref {

    static final AtomicReferenceFieldUpdater<Watch, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Watch.class, Object.class, "state");

    static final IFn WATCH = new AFn() {
        @Override
        public Object invoke(Object key, Object ref, Object prev, Object curr) {
            Watch that = (Watch) key;
            for(;;) {
                Object s = that.state;
                if (s == WATCH) break;
                if (STATE.compareAndSet(that, s, curr)) {
                    if (s == STATE) that.notifier.invoke();
                    break;
                }
            }
            return null;
        }
    };

    IFn notifier;
    IFn terminator;
    IRef reference;
    Object last;

    volatile Object state;

    public Watch (IRef r, IFn n, IFn t) {
        notifier = n;
        terminator = t;
        reference = r;
        state = r.deref();
        n.invoke();
        r.addWatch(this, WATCH);
    }

    @Override
    public Object invoke() {
        for(;;) {
            Object x = state;
            if (x == WATCH) return null;
            last = x;
            if (STATE.compareAndSet(this, x, WATCH)) {
                if (x == STATE) terminator.invoke();
                reference.removeWatch(this);
                return null;
            }
        }
    }

    @Override
    public Object deref() {
        for(;;) {
            Object x = state;
            if (x == WATCH) {
                terminator.invoke();
                return last;
            }
            if (STATE.compareAndSet(this, x, STATE)) return x;
        }
    }
}
