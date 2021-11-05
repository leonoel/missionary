package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import static missionary.impl.Fiber.fiber;

public interface Sequential {

    class Process extends AFn implements Fiber {

        static {
            Util.printDefault(Process.class);
        }

        IFn coroutine;
        IFn success;
        IFn failure;
        IFn resume;
        IFn rethrow;

        boolean busy;
        boolean failed;
        Object current;
        IFn token = Util.NOP;

        @Override
        public synchronized Object invoke() {
            kill(this);
            return null;
        }

        @Override
        public synchronized Object check() {
            return token == null ? clojure.lang.Util.sneakyThrow(new Cancelled("Process cancelled.")) : null;
        }

        @Override
        public Object park(IFn t) {
            return suspend(this, t);
        }

        @Override
        public Object swich(IFn f) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object fork(Number b, IFn f) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object unpark() {
            Object x = current;
            current = null;
            if (failed) {
                failed = false;
                clojure.lang.Util.sneakyThrow((Throwable) x);
            }
            return x;
        }
    }

    static void kill(Process ps) {
        IFn c = ps.token;
        if (c != null) {
            ps.token = null;
            c.invoke();
        }
    }

    static Object suspend(Process ps, IFn task) {
        IFn c = (IFn) task.invoke(ps.resume, ps.rethrow);
        if (ps.token == null) c.invoke();
        else ps.token = c;
        return ps;
    }

    static void step(Process ps) {
        if (ps.busy = !ps.busy) {
            Fiber prev = fiber.get();
            fiber.set(ps);
            try {
                Object x;
                for(;;) if (ps == (x = ps.coroutine.invoke())) if (ps.busy = !ps.busy) {}
                else break; else {
                    ps.success.invoke(x);
                    break;
                }
            } catch (Throwable e) {
                ps.failure.invoke(e);
            }
            fiber.set(prev);
        }
    }

    static Process run(IFn c, IFn s, IFn f) {
        Process ps = new Process();
        synchronized (ps) {
            ps.coroutine = c;
            ps.success = s;
            ps.failure = f;
            ps.resume = new AFn() {
                @Override
                public Object invoke(Object x) {
                    synchronized (ps) {
                        ps.current = x;
                        step(ps);
                        return null;
                    }
                }
            };
            ps.rethrow = new AFn() {
                @Override
                public Object invoke(Object x) {
                    synchronized (ps) {
                        ps.failed = true;
                        ps.current = x;
                        step(ps);
                        return null;
                    }
                }
            };
            step(ps);
            return ps;
        }
    }
}
