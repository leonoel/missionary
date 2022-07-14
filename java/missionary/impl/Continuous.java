package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import missionary.Cancelled;

import static missionary.impl.Fiber.fiber;

public interface Continuous {
    class Process extends AFn implements IDeref, Fiber {

        static {
            Util.printDefault(Process.class);
        }

        IFn notifier;
        IFn terminator;
        IFn coroutine;
        Choice choice;
        boolean live;
        int pending;

        @Override
        public Object invoke() {
            kill(this);
            return null;
        }

        @Override
        public Object deref() {
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
            return live ? null : clojure.lang.Util.sneakyThrow(new Cancelled("Process cancelled."));
        }

        @Override
        public Object unpark() {
            return sample(choice);
        }

    }

    class Choice implements Fiber {
        Process process;
        Choice prev;
        Choice next;
        IFn backtrack;
        Object iterator;
        boolean busy;
        boolean done;

        @Override
        public Object park(IFn task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object swich(IFn flow) {
            return suspend(process, flow);
        }

        @Override
        public Object fork(Number par, IFn flow) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object check() {
            return clojure.lang.Util.sneakyThrow(new Cancelled("Process cancelled."));
        }

        @Override
        public Object unpark() {
            return sample(this);
        }

    }

    IFn getset = new AFn() {
        @Override
        public Object invoke(Object c, Object d) {
            Process ps = (Process) d;
            IFn cr = ps.coroutine;
            ps.coroutine = (IFn) c;
            return cr;
        }
    };

    IFn discard = new AFn() {
        @Override
        public Object invoke(Object c, Object d) {
            IFn cr = (IFn) c;
            Fiber f = fiber.get();
            fiber.set((Choice) d);
            try {
                Object x = cr.invoke();
                if (x instanceof Choice) {
                    Choice ch = (Choice) x;
                    ch.backtrack = cr;
                    ((IFn) ch.iterator).invoke();
                    ready(ch);
                }
            } catch (Throwable e) {}
            fiber.set(f);
            return null;
        }
    };

    static void kill(Process ps) {
        synchronized (ps) {
            if (ps.live) {
                ps.live = false;
                Choice ch = ps.choice;
                if (ch != null) ((IFn) ch.next.iterator).invoke();
            }
        }
    }

    static void top(Choice ch) {
        if (!ch.process.live) ((IFn) ch.iterator).invoke();
    }

    static void done(Choice ch) {
        Process ps = ch.process;
        ps.pending--;
        Choice p = ch.prev;
        if (p != null) {
            ch.prev = null;
            if (ch == p) ps.choice = null; else {
                Choice n = ch.next;
                n.prev = p;
                p.next = n;
                if (ch == ps.choice) ps.choice = p;
                else if (p == ps.choice) top(n);
            }
            ch.next = null;
        }
    }

    static Object sample(Choice ch) {
        try {
            Object x;
            for(;;) {
                x = ((IDeref) ch.iterator).deref();
                if (ch.busy = !ch.busy) {
                    if (ch.done) {
                        done(ch);
                        break;
                    }
                } else break;
            }
            return x;
        } catch (Throwable e) {
            if (ch.busy = !ch.busy) if (ch.done) done(ch);
            throw e;
        }
    }

    static void detach(Choice ch) {
        Process ps = ch.process;
        Choice c;
        while ((c = ps.choice) != ch) {
            Choice p = c.prev;
            Choice n = c.next;
            c.prev = null;
            c.next = null;
            ps.choice = p;
            p.next = n;
            n.prev = p;
            ((IFn) c.iterator).invoke();
        }
    }

    static void ready(Choice ch) {
        if (ch.busy = !ch.busy) if (ch.done) done(ch);
        else if (ch.prev == null) ch.backtrack.invoke(discard, ch);
        else {
            Process ps = ch.process;
            Choice cur = ps.choice;
            detach(ch);
            IFn cr = (IFn) ch.backtrack.invoke(getset, ps);
            if (cr != null) discard.invoke(cr, cur);
        }
    }

    static Object transfer(Process ps) {
        Object x;
        IFn cb;
        synchronized (ps) {
            Fiber f = fiber.get();
            fiber.set(ps);
            try {
                while ((x = ps.coroutine.invoke()) instanceof Choice) {
                    Choice c = (Choice) x;
                    c.backtrack = (IFn) ps.coroutine.invoke(getset, ps);
                    if (c.done || c.busy) {
                        ready(c);
                        ((IFn) c.iterator).invoke();
                        throw new Error("Undefined continuous flow.");
                    } else c.busy = true;
                    Choice p = ps.choice;
                    ps.choice = c;
                    if (p == null) {
                        c.prev = c;
                        c.next = c;
                        top(c);
                    } else {
                        Choice n = p.next;
                        c.prev = p;
                        c.next = n;
                        p.next = c;
                        n.prev = c;
                    }
                }
            } catch (Throwable e) {
                Choice ch = ps.choice;
                if (ch != null) {
                    detach(ch = ch.next);
                    ch.prev = null;
                    ch.next = null;
                    ps.choice = null;
                    ((IFn) ch.iterator).invoke();
                }
                ps.notifier = null;
                x = e;
            }
            fiber.set(f);
            ps.coroutine = null;
            cb = ps.pending == 0 ? ps.terminator : Util.NOP;
        }
        cb.invoke();
        return ps.notifier == null ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }

    static Object suspend(Process ps, IFn flow) {
        Choice ch = new Choice();
        IFn n = new AFn() {
            @Override
            public Object invoke() {
                IFn cb;
                synchronized (ch.process) {
                    IFn cr = ps.coroutine;
                    ready(ch);
                    cb = ps.coroutine == null ? ps.pending == 0 ? ps.terminator
                            : Util.NOP : cr == null ? ps.notifier : Util.NOP;
                }
                return cb.invoke();
            }
        };
        IFn t = new AFn() {
            @Override
            public Object invoke() {
                ch.done = true;
                return n.invoke();
            }
        };
        ch.busy = true;
        ch.process = ps;
        ch.iterator = flow.invoke(n, t);
        ps.pending++;
        return ch;
    }

    static Process run(IFn cr, IFn n, IFn t) {
        Process ps = new Process();
        ps.live = true;
        ps.notifier = n;
        ps.terminator = t;
        ps.coroutine = cr;
        n.invoke();
        return ps;
    }
}