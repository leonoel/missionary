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
            return push(this);
        }
    }

    class Flow extends AFn {
        final IFn cr;

        Flow (IFn cr) {
            this.cr = cr;
        }

        @Override
        public Object invoke(Object n, Object t) {
            Process ps = new Process();
            ps.live = true;
            (ps.notifier = (IFn) n).invoke();
            ps.terminator = (IFn) t;
            ps.value = cr;
            return ps;
        }
    }

    IFn identity = new AFn() {
        @Override
        public Object invoke(Object x) {
            return x;
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

    static Object step(Process ps, IFn cr) {
        Object r;
        Fiber f = fiber.get();
        fiber.set(ps);
        try {
            for(;;) if ((r = (cr = (IFn) cr.invoke(identity)).invoke()) instanceof Choice) {
                Choice ch = (Choice) r;
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
                ch.backtrack = cr;
            } else break;
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
            r = e;
        }
        fiber.set(f);
        return r;
    }

    static Object resume(Choice ch, Object x) {
        IFn cr = ch.backtrack;
        if (ch.prev == null) {
            Object r;
            Fiber f = fiber.get();
            fiber.set(ch);
            try {
                for(;;) if ((r = (cr = (IFn) cr.invoke(identity)).invoke()) instanceof Choice) {
                    ch = (Choice) r;
                    ch.backtrack = cr;
                    ((IFn) ch.iterator).invoke();
                    fiber.set(ch);
                } else break;
            } catch (Throwable e) {}
            fiber.set(f);
            return x;
        } else {
            detach(ch);
            return step(ch.process, cr);
        }
    }

    static void ack(Choice ch) {
        if (ch.done) done(ch);
        else ch.busy = false;
    }

    static Object pull(Choice ch, Object x) {
        try {
            Object r;
            for(;;) {
                r = ((IDeref) ch.iterator).deref();
                if (ch.busy) break;
                else if (ch.done) break;
                else ch.busy = true;
            }
            if (clojure.lang.Util.equiv(ch.value, ch.value = r)) {
                ack(ch);
                return x;
            } else return resume(ch, x);
        } catch (Throwable e) {
            ch.iterator = null;
            ch.value = e;
            return resume(ch, x);
        }
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

    static IFn barrier(Process ps) {
        return ps.pending == 0 ? ps.terminator : null;
    }

    static IFn ready(Choice ch) {
        Process ps = ch.process;
        Choice d = ps.dirty;
        if (ch.busy = !ch.busy) if (ch.done) {
            done(ch);
            return ps.value == ps ? null : barrier(ps);
        } else if (ch.prev == null) {
            pull(ch, null);
            return ps.value == ps ? null : barrier(ps);
        } else if (d == null) {
            ps.dirty = ch;
            return ps.value == ps ? null : ps.notifier;
        } else {
            ps.dirty = link(d, ch);
            return null;
        } else return null;
    }

    static Choice suspend(Process ps, IFn f) {
        Choice ch = new Choice();
        ch.busy = true;
        ch.process = ps;
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
        } else try {
            Object r;
            for(;;) {
                r = ((IDeref) ch.iterator).deref();
                if (ch.busy) break;
                else if (ch.done) break;
                else ch.busy = true;
            }
            ch.value = r;
        } catch (Throwable e) {
            ch.iterator = null;
            ch.value = e;
        } else {
            ((IFn) ch.iterator).invoke();
            throw new Error("Undefined continuous flow.");
        }
        return ch;
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

    static Object push(Choice ch) {
        ack(ch);
        return ch.iterator == null
                ? clojure.lang.Util.sneakyThrow((Throwable) ch.value)
                : ch.value;
    }

    static Object transfer(Process ps) {
        IFn cb;
        Choice d;
        Object x;
        synchronized (ps) {
            x = ps.value;
            ps.value = ps;
            if ((d = ps.dirty) != null) {
                ps.dirty = dequeue(d);
                x = pull(d, x);
            } else x = step(ps, (IFn) x);
            while ((d = ps.dirty) != null) {
                ps.dirty = dequeue(d);
                x = pull(d, x);
            }
            ps.value = x;
            cb = barrier(ps);
        }
        if (cb != null) cb.invoke();
        return ps.notifier == null ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }

    static Flow flow(IFn cr) {
        return new Flow(cr);
    }
}