package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.RT;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public interface Latest {

    AtomicReferenceFieldUpdater<Process, Object> SYNC = AtomicReferenceFieldUpdater.newUpdater(Process.class, Object.class, "sync");

    class Process extends AFn implements IDeref {

        static {
            Util.printDefault(Process.class);
        }

        IFn step;
        IFn done;
        IFn combinator;
        Object value;
        Object owner;
        Object[] args;
        Object[] inputs;
        Object[] children;
        Object[] siblings;
        int pending;
        Integer head;
        volatile Object sync;

        @Override
        public Object invoke() {
            cancel(this);
            return null;
        }

        @Override
        public Object deref() {
            return transfer(this);
        }

    }

    PairingHeap.Impl IMPL = PairingHeap.impl(new AFn() {
        @Override
        public Object invoke(Object ps, Object i, Object j) {
            return (Integer) i < (Integer) j;
        }
    }, new AFn() {
        @Override
        public Object invoke(Object ps, Object x) {
            return ((Process) ps).children[(Integer) x];
        }
    }, new AFn() {
        @Override
        public Object invoke(Object ps, Object x, Object y) {
            ((Process) ps).children[(Integer) x] = y;
            return null;
        }
    }, new AFn() {
        @Override
        public Object invoke(Object ps, Object x) {
            return ((Process) ps).siblings[(Integer) x];
        }
    }, new AFn() {
        @Override
        public Object invoke(Object ps, Object x, Object y) {
            ((Process) ps).siblings[(Integer) x] = y;
            return null;
        }
    });

    Object IDLE = new Object();

    static boolean terminated(Process ps, Integer i) {
        return ps.children[i] == IDLE;
    }

    static void event(Process ps, Integer i) {
        ps.pending -= 1;
        ps.siblings[i] = null;
        if (!terminated(ps, i)) {
            Object h = ps.head;
            ps.head = h == null ? i : (Integer) PairingHeap.meld(IMPL, ps, h, i);
        }
    }

    static void consume(Process ps, Integer i) {
        for(;;) {
            Object s = ps.siblings[i];
            event(ps, i);
            if (s == null) break; else i = (Integer) s;
        }
    }

    static int dequeue(Process ps) {
        ps.pending++;
        Integer h = ps.head;
        ps.head = (Integer) PairingHeap.dmin(IMPL, ps, h);
        ps.siblings[h] = IDLE;
        return h;
    }

    static void cancel(Process ps) {
        for (Object it : ps.inputs) ((IFn) it).invoke();
    }

    static void ready(Process ps) {
        IFn step = ps.step;
        IFn done = ps.done;
        for(;;) if (ps.head == null) if (0 == ps.pending) {
            done.invoke();
            break;
        } else {
            if (SYNC.compareAndSet(ps, null, IDLE)) break; else for(;;) {
                Object s = SYNC.get(ps);
                if (SYNC.compareAndSet(ps, s, null)) {
                    consume(ps, (Integer) s);
                    break;
                }
            }
        } else if (step == null) {
            ps.owner = Thread.currentThread();
            Util.discard(ps.inputs[dequeue(ps)]);
            ps.owner = null;
        } else {
            step.invoke();
            break;
        }
    }

    static Object transfer(Process ps) {
        if (ps.step == null) {
            ready(ps);
            throw new Error("Uninitialized continuous flow.");
        }
        ps.owner = Thread.currentThread();
        try {
            int p = ps.pending;
            if (0 < p) {
                Object s = SYNC.get(ps);
                if (s != null) {
                    while (!SYNC.compareAndSet(ps, s, null))
                        s = SYNC.get(ps);
                    consume(ps, (Integer) s);
                }
            }
            Object x = ps.value;
            Object[] args = ps.args;
            Object[] inputs = ps.inputs;
            while (ps.head != null) {
                int i = dequeue(ps);
                Object prev = args[i];
                Object curr = args[i] = ((IDeref) inputs[i]).deref();
                if (x != ps && !clojure.lang.Util.equiv(prev, curr)) x = ps;
            }
            return x == ps ? ps.value = Util.apply(ps.combinator, args) : x;
        } catch (Throwable e) {
            ps.step = null;
            cancel(ps);
            throw e;
        } finally {
            ps.owner = null;
            ready(ps);
        }
    }

    static void step(Process ps, Integer i) {
        if (ps.owner == Thread.currentThread()) event(ps, i); else for(;;) {
            Object s = SYNC.get(ps);
            if (s == IDLE) {
                if (SYNC.compareAndSet(ps, IDLE, null)) {
                    event(ps, i);
                    ready(ps);
                    break;
                }
            } else {
                ps.siblings[i] = s;
                if (SYNC.compareAndSet(ps, s, i)) break;
            }
        }
    }

    static boolean spawn(Process ps, Integer i, IFn flow) {
        int p = ps.pending;
        ps.args[i] = ps;
        ps.siblings[i] = IDLE;
        ps.inputs[i] = flow.invoke(new AFn() {
            @Override
            public Object invoke() {
                step(ps, i);
                return null;
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                ps.children[i] = IDLE;
                step(ps, i);
                return null;
            }
        });
        return p == ps.pending || terminated(ps, i);
    }

    static Process run(IFn c, Object fs, IFn s, IFn d) {
        int arity = RT.count(fs);
        Iterator it = RT.iter(fs);
        Process ps = new Process();
        ps.done = d;
        ps.combinator = c;
        ps.value = ps;
        ps.pending = arity;
        ps.args = new Object[arity];
        ps.inputs = new Object[arity];
        ps.children = new Object[arity];
        ps.siblings = new Object[arity];
        ps.owner = Thread.currentThread();
        int initialized = 0;
        for (int i = 0; i < arity; i++) initialized = spawn(ps, i, (IFn) it.next()) ? initialized : initialized + 1;
        if (arity == initialized) ps.step = s; else cancel(ps);
        ps.owner = null;
        s.invoke();
        return ps;
    }
}