package missionary.impl;

import clojure.lang.*;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class Sub extends AFn implements IDeref, Subscriber<Object> {

    static final AtomicIntegerFieldUpdater<Sub> stateAtom =
            AtomicIntegerFieldUpdater.newUpdater(Sub.class, "state");

    static final int INIT = 0;
    static final int PULL = 1;
    static final int PUSH = 2;
    static final int DONE = 3;
    static final int STOP = 4;

    IFn terminator;
    IFn notifier;
    Subscription subscription;
    Object current;
    Throwable error;

    volatile int state = INIT;

    public Sub(Publisher<Object> p, IFn n, IFn t) {
        notifier = n;
        terminator = t;
        p.subscribe(this);
    }

    @Override
    public Object invoke() {
        for(;;) {
            switch (state) {
                case INIT: if (stateAtom.compareAndSet(this, INIT, STOP)) {
                    notifier.invoke();
                    return null;
                } else break;
                case PULL: if (stateAtom.compareAndSet(this, PULL, STOP)) {
                    subscription.cancel();
                    notifier.invoke();
                    return null;
                } else break;
                case PUSH: if (stateAtom.compareAndSet(this, PUSH, STOP)) {
                    subscription.cancel();
                    return null;
                } else break;
                default: return null;
            }
        }
    }

    @Override
    public Object deref() {
        for(;;) {
            switch (state) {
                case PUSH: if (stateAtom.compareAndSet(this, PUSH, PULL)) {
                    Object x = current;
                    current = null;
                    subscription.request(1);
                    return x;
                } else break;
                case DONE: {
                    Object x = current;
                    if (x == null) {
                        terminator.invoke();
                        return clojure.lang.Util.sneakyThrow(error);
                    } else {
                        current = null;
                        ((error == null) ? terminator : notifier).invoke();
                        return x;
                    }
                }
                case STOP: {
                    Object x = current;
                    if (x == null) {
                        terminator.invoke();
                        throw new ExceptionInfo("Subscription cancelled.", RT.map(
                                Keyword.intern(null, "cancelled"),
                                Keyword.intern("missionary", "subscribe")
                        ));
                    } else {
                        current = null;
                        notifier.invoke();
                        return x;
                    }
                }
                default: throw new IllegalStateException();
            }
        }
    }

    @Override
    public void onSubscribe(Subscription sub) {
        if (sub == null) throw new NullPointerException();
        for(;;) {
            switch (state) {
                case INIT: if (stateAtom.compareAndSet(this, INIT, PULL)) {
                    subscription = sub;
                    sub.request(1);
                    return;
                } else break;
                default: {
                    sub.cancel();
                    return;
                }
            }
        }
    }

    @Override
    public void onNext(Object x) {
        if (x == null) throw new NullPointerException();
        for(;;) {
            switch (state) {
                case PULL: if (stateAtom.compareAndSet(this, PULL, PUSH)) {
                    current = x;
                    notifier.invoke();
                    return;
                } else break;
                case DONE: return;
                default: throw new IllegalStateException();
            }
        }
    }

    @Override
    public void onError(Throwable e) {
        if (e == null) throw new NullPointerException();
        error = e;
        onComplete();
    }

    @Override
    public void onComplete() {
        for(;;) {
            switch (state) {
                case PULL: if (stateAtom.compareAndSet(this, PULL, DONE)) {
                    notifier.invoke();
                    return;
                } else break;
                case PUSH: if (stateAtom.compareAndSet(this, PUSH, DONE)) {
                    return;
                } else break;
                case STOP: return;
                default: throw new IllegalStateException();
            }
        }
    }
}
