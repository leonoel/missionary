package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IFn;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class Thunk extends AFn {

    static final class Cpu extends Thread {
        static final AtomicInteger ID = new AtomicInteger();
        static final Executor POOL = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
            Thread t = new Thread(r, "missionary cpu-" + ID.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    static final class Blk extends Thread {
        static final AtomicInteger ID = new AtomicInteger();
        static final Executor POOL = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "missionary blk-" + ID.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    static final AtomicReferenceFieldUpdater<Thunk, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Thunk.class, Object.class, "state");

    IFn thunk;
    IFn success;
    IFn failure;

    volatile Object state = this;

    public Thunk(Executor e, IFn t, IFn s, IFn f) {
        thunk = t;
        success = s;
        failure = f;
        e.execute(this);
    }

    @Override
    public void run() {
        Thread t = Thread.currentThread();
        for(;;) {
            Object x = state;
            if (x == null) {
                t.interrupt();
                break;
            } else if (STATE.compareAndSet(this, x, t)) break;
        }
        try {
            success.invoke(thunk.invoke());
        } catch (Throwable error) {
            failure.invoke(error);
        }
        invoke();
        Thread.interrupted();
    }

    @Override
    public Object invoke() {
        for(;;) {
            Object x = state;
            if (x == null) break;
            if (STATE.compareAndSet(this, x, null)) {
                if (x != this) ((Thread) x).interrupt();
                break;
            }
        }
        return null;
    }

    public static Executor cpu = Cpu.POOL;
    public static Executor blk = Blk.POOL;

}
