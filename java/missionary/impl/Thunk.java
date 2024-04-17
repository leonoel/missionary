package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IFn;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public interface Thunk {

    final class Cpu extends Thread {
        static final AtomicInteger ID = new AtomicInteger();
        static final Executor POOL = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
            Thread t = new Thread(r, "missionary cpu-" + ID.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    final class Blk extends Thread {
        static final AtomicInteger ID = new AtomicInteger();
        static final Executor POOL = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "missionary blk-" + ID.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    Executor cpu = Cpu.POOL;
    Executor blk = Blk.POOL;

    final class Process extends AFn {
        static {
            Util.printDefault(Process.class);
        }

        final IFn thunk;
        final IFn success;
        final IFn failure;

        Object thread;

        public Process(Executor e, IFn t, IFn s, IFn f) {
            thunk = t;
            success = s;
            failure = f;
            e.execute(this);
        }

        @Override
        public void run() {
            synchronized (this) {
                Thread t = Thread.currentThread();
                if (thread == this) t.interrupt();
                else thread = t;
            }
            Object x;
            IFn cont;
            try {
                x = thunk.invoke();
                cont = success;
            } catch (Throwable e) {
                x = e;
                cont = failure;
            }
            synchronized (this) {
                if (thread == this) Thread.interrupted();
                else thread = this;
            }
            cont.invoke(x);
        }

        @Override
        public Object invoke() {
            synchronized (this) {
                Object t = thread;
                if (t != this) {
                    thread = this;
                    if (t != null) ((Thread) t).interrupt();
                }
            }
            return null;
        }
    }

    static Process run(Executor e, IFn t, IFn s, IFn f) {
        return new Process(e, t, s, f);
    }
}
