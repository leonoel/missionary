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

    class Choice {
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

    static void detach(Choice ch) {
        Process ps = ch.process;
        Choice p = ch.prev;
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

    static void prune(Choice ch) {
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
            for(;;) if ((r = (cr = (IFn) cr.invoke(identity)).invoke()) == ps)
                ps.choice.backtrack = cr;
            else break;
        } catch (Throwable e) {
            Choice ch = ps.choice;
            if (ch != null) {
                prune(ch = ch.next);
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

    static Object pull(Choice ch, Object x) {
        Process ps = ch.process;
        IFn bt = ch.backtrack;
        try {
            for(;;) {
                Object r = ((IDeref) ch.iterator).deref();
                if (ch.busy) if (clojure.lang.Util.equiv(ch.value, ch.value = r)) {
                    ch.busy = false;
                    return x;
                } else {
                    prune(ch);
                    return step(ps, bt);
                } else if (ch.done) if (clojure.lang.Util.equiv(ch.value, ch.value = r)) {
                    ps.pending--;
                    detach(ch);
                    return x;
                } else {
                    prune(ch);
                    return step(ps, bt);
                } else ch.busy = true;
            }
        } catch (Throwable e) {
            ch.iterator = null;
            ch.value = e;
            prune(ch);
            return step(ps, bt);
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

    static IFn ready(Choice ch) {
        Process ps = ch.process;
        Choice d;
        for(;;) if (ch.busy = !ch.busy) if (ch.done) {
            if (ch.prev != null) detach(ch);
            return --ps.pending == 0 ? ps.value == ps ? null : ps.terminator : null;
        } else if (ch.prev == null) {
            Util.discard(ch.iterator);
        } else if ((d = ps.dirty) == null) {
            ps.dirty = ch;
            return ps.value == ps ? null : ps.notifier;
        } else {
            ps.dirty = link(d, ch);
            return null;
        } else return null;
    }

    static Object suspend(Process ps, IFn f) {
        Choice ch = new Choice();
        ch.busy = true;
        ch.value = ps;
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
        Choice p = ps.choice;
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
        ps.choice = ch;
        ps.pending++;
        return ps;
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
        Process ps = ch.process;
        Object x = ch.value;
        if (x == ps) if (ch.busy = !ch.busy) if (ch.done) {
            ps.pending--;
            detach(ch);
            throw new Error("Undefined continuous flow.");
        } else try {
            for(;;) {
                x = ((IDeref) ch.iterator).deref();
                if (ch.busy) {
                    ch.busy = false;
                    break;
                } else if (ch.done) {
                    ps.pending--;
                    detach(ch);
                    break;
                } else ch.busy = true;
            }
            return ch.value = x;
        } catch (Throwable e) {
            if (ch.done) {
                ps.pending--;
                detach(ch);
            } else ch.busy = false;
            throw e;
        } else {
            detach(ch);
            ((IFn) ch.iterator).invoke();
            throw new Error("Undefined continuous flow.");
        } else {
            if (ch.done) {
                ps.pending--;
                detach(ch);
            } else ch.busy = false;
            return ch.iterator == null ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
        }
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
                if (d.prev == null) {
                    Util.discard(d.iterator);
                    ready(d);
                } else x = pull(d, x);
            }
            ps.value = x;
            cb = ps.pending == 0 ? ps.terminator : null;
        }
        if (cb != null) cb.invoke();
        return ps.notifier == null ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }

    static Flow flow(IFn cr) {
        return new Flow(cr);
    }
}