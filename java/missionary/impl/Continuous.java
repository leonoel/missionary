package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import missionary.Cancelled;

import static missionary.impl.Fiber.fiber;
import static missionary.impl.Util.NOP;

public interface Continuous {
    class Process extends AFn implements Fiber, IDeref {

        static {
            Util.printDefault(Process.class);
        }

        IFn notifier;
        IFn terminator;
        Choice choice;

        @Override
        public synchronized Object invoke() {
            kill(this);
            return null;
        }

        @Override
        public synchronized Object deref() {
            return transfer(this);
        }

        @Override
        public Object park(IFn task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object swich(IFn flow) {
            return suspend(this, flow);
        }

        @Override
        public Object fork(Number par, IFn flow) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object check() {
            return choice.live ? null : clojure.lang.Util.sneakyThrow(new Cancelled("Process cancelled."));
        }

        @Override
        public Object unpark() {
            return ((IDeref) choice.prev.iterator).deref();
        }
    }

    class Choice {
        Process process;
        Choice prev;
        Choice next;
        IFn coroutine;
        Object iterator;
        boolean live;
        boolean busy;
        boolean done;
    }

    IFn boot = new AFn() {
        @Override
        public Object invoke(Object c, Object p) {
            Process ps = (Process) p;
            Choice curr = new Choice();
            Choice prev = curr.prev = ps.choice;
            Choice next = curr.next = prev.next;
            curr.busy = true;
            curr.process = ps;
            curr.iterator = NOP;
            curr.live = prev.live;
            curr.coroutine = (IFn) c;
            ps.choice = prev.next = next.prev = curr;
            return null;
        }
    };

    static void cancel(Choice c) {
        Process ps = c.process;
        for(;;) {
            if (!c.live) break;
            c.live = false;
            ((IFn) c.iterator).invoke();
            Choice t = ps.choice;
            if (t == null) break;
            do c = c.next; while (c.prev == null);
            if (c == t.next) break;
        }
    }

    static void kill(Process ps) {
        Choice c = ps.choice;
        if (c != null) cancel(c.next);
    }

    static void done(Process ps) {
        Choice c = ps.choice;
        Choice p = c.prev;
        c.prev = null;
        if (p == c) {
            ps.choice = null;
            ps.terminator.invoke();
        } else {
            Choice n = c.next;
            n.prev = p;
            p.next = n;
            ps.choice = p;
            ack(p);
        }
    }

    static void dirty(Process ps) {
        IFn n = ps.notifier;
        ps.choice.coroutine.invoke(boot, ps);
        if (n == null) {
            Fiber prev = fiber.get();
            fiber.set(ps);
            try {
                while (ps.choice.coroutine.invoke() == ps);
            } catch (Throwable e) {}
            fiber.set(prev);
            done(ps);
        } else n.invoke();
    }

    static void ack(Choice c) {
        if (c.busy && c.done) {
            c.busy = c.done = false;
            dirty(c.process);
        }
    }

    static void ready(Choice c) {
        if (c.busy = !c.busy) {
            IFn cr = c.coroutine;
            Process ps = c.process;
            if (c.done) {
                Choice p = c.prev;
                c.prev = null;
                if (c == p) {
                    ps.choice = null;
                    ps.terminator.invoke();
                } else {
                    Choice n = c.next;
                    n.prev = p;
                    p.next = n;
                    if (c == ps.choice) {
                        ps.choice = p;
                        ack(p);
                    }
                }
            } else if (cr == null) {
                c.busy = false;
                Util.discard(c.iterator);
            } else if (c == ps.choice) {
                c.busy = false;
                dirty(ps);
            } else {
                c.done = true;
                cancel(c.next);
            }
        }
    }

    static Object transfer(Process ps) {
        Fiber prev = fiber.get();
        fiber.set(ps);
        try {
            Object x;
            do x = ps.choice.coroutine.invoke(); while (x == ps);
            return x;
        } catch (Throwable e) {
            ps.notifier = null;
            kill(ps);
            throw e;
        } finally {
            fiber.set(prev);
            done(ps);
        }
    }

    static Object suspend(Process ps, IFn flow) {
        Choice c = ps.choice;
        c.iterator = flow.invoke(new AFn() {
            @Override
            public Object invoke() {
                synchronized (c.process) {
                    ready(c);
                    return null;
                }
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                synchronized (c.process) {
                    c.done = true;
                    ready(c);
                    return null;
                }
            }
        });
        c.coroutine.invoke(boot, ps);
        if (c.done) {
            ready(c);
            throw new Error("Undefined continuous flow.");
        }
        if (c.busy) {
            c.busy = false;
            c.coroutine = null;
            ((IFn) c.iterator).invoke();
            throw new Error("Undefined continuous flow.");
        }
        if (!c.live) ((IFn) c.iterator).invoke();
        return ps;
    }

    static Process run(IFn cr, IFn n, IFn t) {
        Process ps = new Process();
        Choice c = new Choice();
        c.busy = c.live = true;
        c.prev = c.next = c;
        c.process = ps;
        c.coroutine = cr;
        c.iterator = NOP;
        ps.choice = c;
        ps.notifier = n;
        ps.terminator = t;
        n.invoke();
        return ps;
    }
}