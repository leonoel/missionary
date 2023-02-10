package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IFn;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

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

    AtomicReferenceFieldUpdater<Process, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(Process.class, Object.class, "state");

    final class Process extends AFn {
        static {
            Util.printDefault(Process.class);
        }

        IFn thunk;
        IFn success;
        IFn failure;

        volatile Object state = this;

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
            cancel(this);
            Thread.interrupted();
        }

        @Override
        public Object invoke() {
            cancel(this);
            return null;
        }
    }

    static void cancel(Process ps) {
        for(;;) {
            Object x = ps.state;
            if (x == null) break;
            if (STATE.compareAndSet(ps, x, null)) {
                if (x != ps) ((Thread) x).interrupt();
                break;
            }
        }
    }

    static Process run(Executor e, IFn t, IFn s, IFn f) {
        Process ps = new Process();
        ps.thunk = t;
        ps.success = s;
        ps.failure = f;
        e.execute(ps);
        return ps;
    }
}
