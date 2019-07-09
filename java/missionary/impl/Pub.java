package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

public final class Pub implements Subscription {
    static final AtomicIntegerFieldUpdater<Pub> PRESSURE =
            AtomicIntegerFieldUpdater.newUpdater(Pub.class, "pressure");
    static final AtomicLongFieldUpdater<Pub> REQUESTED =
            AtomicLongFieldUpdater.newUpdater(Pub.class, "requested");
    static final Throwable negativeRequest =
            new IllegalArgumentException("Negative subscription request (3.9)");

    Subscriber<Object> subscriber;
    Object iterator;
    Throwable error;
    boolean done;

    volatile int pressure;
    volatile long requested;

    void pull() {
        do {
            long r = requested;
            if (done) switch ((int) r) {
                case -2:
                    subscriber.onError(negativeRequest);
                    break;
                case -1:
                    break;
                default:
                    if (error == null) subscriber.onComplete();
                    else subscriber.onError(error);
            }
            else if (r < 0) try {((IDeref) iterator).deref();} catch (Throwable ignored) {}
            else try {
                subscriber.onNext(((IDeref) iterator).deref());
                for(;;) {
                    if (REQUESTED.compareAndSet(this, r, r - 1)) {
                        if (r == 1) return;
                        else break;
                    } else {
                        r = requested;
                        if (r < 0) break;
                    }
                }
            } catch (Throwable e) {
                error = e;
            }
        } while (0 == PRESSURE.decrementAndGet(this));
    }

    void kill(int code) {
        for(;;) {
            long p = requested;
            if (p < 0) break;
            else if (REQUESTED.compareAndSet(this, p, code)) {
                ((IFn) iterator).invoke();
                if (0 == p && 0 == PRESSURE.decrementAndGet(this)) pull();
                break;
            }
        }
    }

    public Pub(IFn f, Subscriber<Object> s) {
        subscriber = s;
        iterator = f.invoke(
                new AFn() {
                    @Override
                    public Object invoke() {
                        if (0 == PRESSURE.incrementAndGet(Pub.this)) pull();
                        return null;
                    }
                },
                new AFn() {
                    @Override
                    public Object invoke() {
                        done = true;
                        if (0 == PRESSURE.incrementAndGet(Pub.this)) pull();
                        return null;
                    }
                });
        s.onSubscribe(this);
    }

    @Override
    public void request(long n) {
        if (0 < n) for(;;) {
            long p = requested;
            if (p < 0) break;
            long r = p + n;
            if (n < 0) r = Long.MAX_VALUE;
            if (REQUESTED.compareAndSet(this, p, r)) {
                if (0 == p && 0 == PRESSURE.decrementAndGet(this)) pull();
                break;
            }
        } else kill(-2);
    }

    @Override
    public void cancel() {
        kill(-1);
    }

}
