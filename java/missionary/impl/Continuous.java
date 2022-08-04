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
        Object value;
        Choice choice;
        Choice dirty;
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
            return attach(this, flow);
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
            return push(choice);
        }

    }

    class Choice implements Fiber {
        Process process;
        Choice prev;
        Choice next;
        Choice child;
        Choice sibling;
        IFn backtrack;
        Object iterator;
        Object value;
        boolean busy;
        boolean done;
        int rank;

        @Override
        public Object park(IFn task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object swich(IFn flow) {
            return cancel(process, flow);
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
            return push(this);
        }
    }

    IFn resume = new AFn() {
        @Override
        public Object invoke(Object c, Object p) {
            ((Process) p).value = c;
            return null;
        }
    };

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

    static Choice link(Choice x, Choice y) {
        if (x.rank < y.rank) {
            y.sibling = x.child;
            x.child = y;
            return x;
        } else {
            x.sibling = y.child;
            y.child = x;
            return y;
        }
    }

    static Object pull(Choice ch) {
        Object p = ch.value;
        Object x;
        try {
            for(;;) {
                x = ((IDeref) ch.iterator).deref();
                if (ch.busy) break;
                else if (ch.done) break;
                else ch.busy = true;
            }
        } catch (Throwable e) {
            ch.iterator = null;
            x = e;
        }
        ch.value = x;
        return p;
    }

    static void detach(Choice ch) {
        Choice c;
        Process ps = ch.process;
        while ((c = ps.choice) != ch) {
            Choice p = c.prev;
            Choice n = c.next;
            c.prev = null;
            c.next = null;
            p.next = n;
            n.prev = p;
            ps.choice = p;
            ((IFn) c.iterator).invoke();
        }
    }

    static void step(Process ps) {
        Fiber f = fiber.get();
        fiber.set(ps);
        Object x;
        try {
            while ((x = ((IFn) ps.value).invoke()) == ps);
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
        ps.value = x;
        fiber.set(f);
    }

    static Choice dequeue(Choice c) {
        Choice heap = null;
        Choice prev = null;
        Choice head = c.child;
        c.child = null;
        while (head != null) {
            Choice next = head.sibling;
            head.sibling = null;
            if (prev == null) prev = head;
            else {
                head = link(prev, head);
                heap = heap == null ? head : link(heap, head);
                prev = null;
            }
            head = next;
        }
        return prev == null ? heap : heap == null ? prev : link(heap, prev);
    }

    static void discard(Choice ch) {
        pull(ch);
        Process ps = ch.process;
        Fiber f = fiber.get();
        fiber.set(ch);
        Object p = ps.value;
        ch.backtrack.invoke(resume, ps);
        try {
            ((IFn) ps.value).invoke();
        } catch (Throwable e) {}
        ps.value = p;
        fiber.set(f);
    }

    static IFn barrier(Process ps) {
        return ps.pending == 0 ? ps.terminator : null;
    }

    static IFn ready(Choice ch) {
        Process ps = ch.process;
        Choice d = ps.dirty;
        if (ch.busy = !ch.busy) if (ch.done) {
            done(ch);
            return barrier(ps);
        } else if (ch.prev == null) {
            discard(ch);
            return barrier(ps);
        } else if (d == null) {
            ps.dirty = ch;
            return ps.notifier;
        } else {
            ps.dirty = link(d, ch);
            return null;
        } else return null;
    }

    static Choice suspend(Process ps, IFn f) {
        Choice ch = new Choice();
        ch.busy = true;
        ch.process = ps;
        ch.backtrack = (IFn) ps.value;
        ch.iterator = f.invoke(new AFn() {
            @Override
            public Object invoke() {
                IFn cb;
                synchronized (ch.process) {
                    cb = ready(ch);
                }
                return cb == null ? null : cb.invoke();
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                IFn cb;
                synchronized (ch.process) {
                    ch.done = true;
                    cb = ready(ch);
                }
                return cb == null ? null : cb.invoke();
            }
        });
        ps.pending++;
        if (ch.busy = !ch.busy) if (ch.done) {
            done(ch);
            throw new Error("Undefined continuous flow.");
        } else return ch; else {
            ((IFn) ch.iterator).invoke();
            throw new Error("Undefined continuous flow.");
        }
    }

    static void kill(Process ps) {
        synchronized (ps) {
            if (ps.live) {
                ps.live = false;
                Choice ch = ps.choice;
                if (ch != null) ((IFn) ch.next.iterator).invoke();
            }
        }
    }

    static void ack(Choice ch) {
        if (ch.done) done(ch);
        else ch.busy = false;
    }

    static Object push(Choice ch) {
        ack(ch);
        return ch.iterator == null
                ? clojure.lang.Util.sneakyThrow((Throwable) ch.value)
                : ch.value;
    }

    static Object transfer(Process ps) {
        IFn cb;
        Object x;
        synchronized (ps) {
            ps.pending++;
            Choice d = ps.dirty;
            if (d == null) step(ps); else do {
                ps.dirty = dequeue(d);
                if (d.prev == null) discard(d);
                else if (clojure.lang.Util.equiv(pull(d), d.value) && d.iterator != null) ack(d);
                else {
                    d.backtrack.invoke(resume, ps);
                    detach(d);
                    step(ps);
                }
            } while ((d = ps.dirty) != null);
            ps.pending--;
            x = ps.value;
            cb = barrier(ps);
        }
        if (cb != null) cb.invoke();
        return ps.notifier == null ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }

    static Object attach(Process ps, IFn flow) {
        Choice ch = suspend(ps, flow);
        Choice p = ps.choice;
        ps.choice = ch;
        if (p == null) {
            ch.prev = ch;
            ch.next = ch;
            top(ch);
        } else {
            Choice n = p.next;
            ch.prev = p;
            ch.next = n;
            p.next = ch;
            n.prev = ch;
            ch.rank = p.rank + 1;
        }
        ch.backtrack.invoke(resume, ps);
        pull(ch);
        return ps;
    }

    static Object cancel(Process ps, IFn flow) {
        Choice ch = suspend(ps, flow);
        ((IFn) ch.iterator).invoke();
        discard(ch);
        return null;
    }

    static Process run(IFn cr, IFn n, IFn t) {
        Process ps = new Process();
        ps.live = true;
        ps.notifier = n;
        ps.terminator = t;
        ps.value = cr;
        n.invoke();
        return ps;
    }
}