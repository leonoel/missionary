package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import static missionary.impl.Fiber.fiber;
import static missionary.impl.Util.NOP;

public interface Ambiguous {

    final class Process extends AFn implements IDeref {

        static {
            Util.printDefault(Process.class);
        }

        IFn notifier;
        IFn terminator;
        Branch head;
        Branch tail;
        Branch child;

        @Override
        public synchronized Object invoke() {
            kill(this);
            return null;
        }

        @Override
        public synchronized Object deref() {
            return transfer(this);
        }
    }

    final class Branch implements Fiber {
        Object parent;
        Branch prev;
        Branch next;
        Branch queue;
        Choice choice;
        Object current;

        @Override
        public Object check() {
            return choice.live ? null : clojure.lang.Util.sneakyThrow(new Cancelled("Process cancelled."));
        }

        @Override
        public Object park(IFn task) {
            return suspend(this, null, task);
        }

        @Override
        public Object swich(IFn flow) {
            return suspend(this, -1, flow);
        }

        @Override
        public Object fork(Number par, IFn flow) {
            return suspend(this, par, flow);
        }

        @Override
        public Object unpark() {
            return resume(this);
        }
    }

    final class Choice {
        Branch branch;
        Choice prev;
        Choice next;
        IFn coroutine;
        Object iterator;
        Number parallelism;
        boolean live;
        boolean busy;
        boolean done;
    }

    final class Processor {
        Branch branch;
        Processor prev;
        Processor next;
        Branch child;
    }

    IFn boot = new AFn() {
        @Override
        public Object invoke(Object cr, Object c) {
            Choice choice = (Choice) c;
            choice.coroutine = (IFn) cr;
            ready(choice);
            return null;
        }
    };

    static void backtrack(Choice p, Branch b, Choice c) {
        try {
            c.iterator = NOP;
            b.choice = c;
            b.current = ((IDeref) p.iterator).deref();
            p.coroutine.invoke(boot, c);
        } catch (Throwable e) {
            c.done = true;
            b.current = e;
            boot.invoke(p.coroutine, c);
        }
    }

    static void choose(Choice p) {
        Branch b = p.branch;
        Choice n = p.next;
        Choice c = new Choice();
        c.prev = p;
        c.next = n;
        c.branch = b;
        c.live = p.live;
        p.next = n.prev = c;
        backtrack(p, b, c);
    }

    static void branch(Choice p) {
        p.parallelism = Numbers.dec(p.parallelism);
        Branch parent = p.branch;
        Processor prev = (Processor) parent.current;
        Processor curr = new Processor();
        curr.branch = parent;
        Branch b = new Branch();
        b.parent = curr;
        Choice c = new Choice();
        c.branch = b;
        c.live = p.live;
        parent.current = curr;
        if (prev == null) curr.prev = curr.next = curr;
        else {
            Processor next = prev.next;
            prev.next = next.prev = curr;
            curr.prev = prev;
            curr.next = next;
        }
        curr.child = b.prev = b.next = b;
        c.prev = c.next = c;
        backtrack(p, b, c);
    }

    static Process root(Branch b) {
        Object node = b.parent;
        for(;;) if (node instanceof Processor) node = ((Processor) node).branch.parent;
        else if (node instanceof Branch) node = ((Branch) node).parent; else break;
        return (Process) node;
    }

    static void kill(Process ps) {
        Branch b = ps.child;
        if (b != null) walk(b.next);
    }

    static void cancel(Choice c) {
        Branch b = c.branch;
        for(;;) {
            if (!c.live) break;
            c.live = false;
            ((IFn) c.iterator).invoke();
            Choice t = b.choice;
            if (t == null) break;
            do c = c.next; while (c.prev == null);
            if (c == t.next) {
                Object curr = b.current;
                if (curr instanceof Processor) {
                    Processor pr = ((Processor) curr).next;
                    for(;;) {
                        walk(pr.child.next);
                        curr = b.current;
                        if (curr == null) break;
                        do pr = pr.next; while (pr.prev == null);
                        if (pr == ((Processor) curr).next) break;
                    }
                } else if (curr instanceof Branch) walk(((Branch) curr).next);
                break;
            }
        }
    }

    static void walk(Branch b) {
        for(;;) {
            cancel(b.choice.next);
            Object node = b.parent;
            Branch t = node instanceof Processor ? ((Processor) node).child :
                    node instanceof Branch ? (Branch) ((Branch) node).current : ((Process) node).child;
            if (t == null) break;
            do b = b.next; while (b.prev == null);
            if (b == t.next) break;
        }
    }

    static void move(Branch x, Branch y) {
        Object p = x.parent;
        Branch b = y;
        do (b = b.next).parent = p; while (b != y);
        Branch xx = x.next;
        Branch yy = y.next;
        (x.next = yy).prev = x;
        (y.next = xx).prev = y;
    }

    static void discard(Branch b) {
        Object parent = b.parent;
        Branch prev = b.prev;
        Branch next = b.next;
        b.prev = null;
        b.choice = null;
        b.current = null;
        if (parent instanceof Branch) {
            Branch br = (Branch) parent;
            if (b == prev) {
                Choice c = br.choice;
                br.current = null;
                if (c.busy && c.done) {
                    c.busy = c.done = false;
                    choose(c);
                }
            } else {
                prev.next = next;
                next.prev = prev;
                if (br.current == b) br.current = prev;
            }
        } else if (parent instanceof Processor) {
            Processor pr = (Processor) parent;
            if (b == prev) {
                b = pr.branch;
                Choice c = b.choice;
                Processor p = pr.prev;
                Processor n = pr.next;
                pr.child = null;
                pr.prev = null;
                if (pr == p) b.current = null; else {
                    p.next = n;
                    n.prev = p;
                    b.current = p;
                }
                c.parallelism = Numbers.inc(c.parallelism);
                if (c.busy && c.done) {
                    c.busy = c.done = false;
                    branch(c);
                }
            } else {
                prev.next = next;
                next.prev = prev;
                if (pr.child == b) pr.child = prev;
            }
        } else {
            Process ps = (Process) parent;
            if (b == prev) {
                ps.child = null;
                ps.terminator.invoke();
            } else {
                prev.next = next;
                next.prev = prev;
                if (ps.child == b) ps.child = prev;
            }
        }
    }

    static void ack(Choice c) {
        if (c.busy && c.done) {
            c.busy = c.done = false;
            if (Numbers.isNeg(c.parallelism)) choose(c);
            else branch(c);
        }
    }

    static Branch done(Branch b) {
        Branch q = b.queue;
        Choice c = b.choice;
        Choice p = c.prev;
        c.prev = null;
        b.queue = null;
        if (p == c) discard(b); else {
            Choice n = c.next;
            n.prev = p;
            p.next = n;
            b.choice = p;
            b.current = null;
            ack(p);
        }
        return q;
    }

    static Object transfer(Process ps) {
        Branch b = ps.head;
        Object x = b.current;
        if (b.choice.done) {
            ps.notifier = null;
            kill(ps);
            b = done(b);
            while (b != null) b = done(b);
            b = ps.tail;
            while (b != null) b = done(b);
            ps.head = ps.tail = null;
            clojure.lang.Util.sneakyThrow((Throwable) x);
        }
        Branch next = done(b);
        if (next == null) {
            Branch prev = ps.tail;
            if (prev == null) ps.head = null;
            else {
                do {
                    Branch swap = prev.queue;
                    prev.queue = next;
                    next = prev;
                    prev = swap;
                } while (prev != null);
                ps.tail = null;
                ps.head = next;
                ps.notifier.invoke();
            }
        } else {
            ps.head = next;
            ps.notifier.invoke();
        }
        return x;
    }

    static void ready(Choice c) {
        if (c.busy = !c.busy) for(;;) {
            Branch b = c.branch;
            Number par = c.parallelism;
            Object curr = b.current;
            if (par == null) {
                Object x;
                Fiber prev = fiber.get();
                fiber.set(b);
                try {
                    x = c.coroutine.invoke();
                } catch (Throwable e) {
                    c.done = true;
                    x = e;
                }
                fiber.set(prev);
                if (x == b) if (c.busy = !c.busy) {} else break; else {
                    b.current = x;
                    Process ps = root(b);
                    IFn n = ps.notifier;
                    if (n == null) done(b); else if (ps.head == null) {
                        ps.head = b;
                        n.invoke();
                    } else {
                        b.queue = ps.tail;
                        ps.tail = b;
                    }
                    break;
                }
            } else if (c.done) {
                Choice p = c.prev;
                c.prev = null;
                if (c == p) {
                    if (curr instanceof Branch) move(b, (Branch) curr);
                    else if (curr instanceof Processor) {
                        Processor pr = (Processor) curr;
                        do move(b, (pr = pr.next).child); while (pr != b.current);
                    }
                    discard(b);
                } else {
                    Choice n = c.next;
                    n.prev = p;
                    p.next = n;
                    if (c == b.choice) {
                        b.choice = p;
                        if (curr == null) ack(p); else if (curr instanceof Processor) {
                            Processor pr = (Processor) curr;
                            Branch pivot = pr.child;
                            b.current = pivot;
                            pivot.parent = b;
                            while ((pr = pr.next) != curr) move(pivot, pr.child);
                            pr.prev = pr.next = pr;
                        }
                    }
                }
                break;
            } else if (Numbers.isPos(par)) {
                c.busy = false;
                branch(c);
                break;
            } else if (Numbers.isNeg(par)) if (c == b.choice) if (curr == null) {
                c.busy = false;
                choose(c);
                break;
            } else {
                c.done = true;
                walk(((Branch) curr).next);
                break;
            } else {
                c.done = true;
                cancel(c.next);
                break;
            } else {
                c.done = true;
                break;
            }
        }
    }

    static Object suspend(Branch b, Number par, IFn flow) {
        Choice c = b.choice;
        c.parallelism = par;
        c.iterator = flow.invoke(new AFn() {
            @Override
            public Object invoke() {
                Branch b = c.branch;
                synchronized (root(b)) {
                    ready(c);
                    return null;
                }
            }
            @Override
            public Object invoke(Object x) {
                Branch b = c.branch;
                synchronized (root(b)) {
                    b.current = x;
                    ready(c);
                    return null;
                }
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                Branch b = c.branch;
                synchronized (root(b)) {
                    c.done = true;
                    ready(c);
                    return null;
                }
            }
            @Override
            public Object invoke(Object x) {
                Branch b = c.branch;
                synchronized (root(b)) {
                    c.done = true;
                    b.current = x;
                    ready(c);
                    return null;
                }
            }
        });
        if (!c.live) ((IFn) c.iterator).invoke();
        return b;
    }

    static Object resume(Branch b) {
        Choice c = b.choice;
        Object x = b.current;
        b.current = null;
        if (c.done) {
            c.done = false;
            clojure.lang.Util.sneakyThrow((Throwable) x);
        }
        return x;
    }

    static Object run(IFn cr, IFn n, IFn t) {
        Process ps = new Process();
        synchronized (ps) {
            Branch b = new Branch();
            Choice c = new Choice();
            c.iterator = NOP;
            c.live = true;
            c.branch = b;
            b.parent = ps;
            ps.notifier = n;
            ps.terminator = t;
            ps.child = b.prev = b.next = b;
            b.choice = c.prev = c.next = c;
            boot.invoke(cr, c);
            return ps;
        }
    }
}