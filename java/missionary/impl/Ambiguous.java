package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Fiber.CURRENT;
import static missionary.impl.Util.NOP;

public interface Ambiguous {

    static IFn ready(Choice c) {
        IFn s;
        while (!Choice.READY.compareAndSet(c, s = c.ready, s == null ? NOP : null));
        return s;
    }

    static void terminate(Gather g) {
        Object a;
        while (!Process.ALIVE.compareAndSet(g.process, a = g.process.alive, a instanceof Integer ?
                ((Integer) a) - 1 : ((IPersistentSet) a).disjoin(g)));
        if (1 == (a instanceof Integer ? (Integer) a : ((IPersistentSet) a).count()))
            g.process.terminator.invoke();
    }

    static void emit(Gather g, Object x) {
        g.current = x;
        Object q;
        for(;;) {
            q = g.process.queue;
            if (q == Process.QUEUE) {
                if (Process.QUEUE.compareAndSet(g.process, q, null)) {
                    g.next = null;
                    g.process.head = g;
                    g.process.notifier.invoke();
                    break;
                }
            } else {
                g.next = (Gather) q;
                if (Process.QUEUE.compareAndSet(g.process, q, g)) break;
            }
        }
    }

    static void step(Gather g) {
        Fiber prev = CURRENT.get();
        CURRENT.set(g);
        do try {
            Object x = g.coroutine.invoke();
            if (x != CURRENT) emit(g, x);
        } catch (Throwable e) {
            g.failed = true;
            emit(g, e);
        } while (0 == Gather.PRESSURE.decrementAndGet(g));
        CURRENT.set(prev);
    }

    static void more(Gather g) {
        do if (g.choice.done) {
            g.choice = g.choice.parent;
            if (g.choice == null) {
                terminate(g);
                return;
            }
        } else if (g.failed) try {
            ((IDeref) g.choice.iterator).deref();
        } catch (Throwable _) {
        } else try {
            Object x = ((IDeref) g.choice.iterator).deref();
            Object q = null;
            if (g.choice.type == Choice.GATHER) while ((q = g.process.queue) != Process.QUEUE &&
                    !Process.QUEUE.compareAndSet(g.process, g.next = (Gather) q, g));
            g.choice.backtrack.invoke(RUN, g.choice.type == Choice.GATHER ? gather(g.process) : g, x);
            if (q != Process.QUEUE) return;
        } catch (Throwable e) {
            g.coroutine = g.choice.backtrack;
            g.failed = true;
            g.current = e;
            if (0 == Gather.PRESSURE.incrementAndGet(g)) step(g);
            return;
        } while (null == ready(g.choice));
    }

    static void choice(Gather g, IFn flow, int type) {
        Choice c = new Choice();
        c.parent = g.choice;
        c.backtrack = g.coroutine;
        c.iterator = flow.invoke(new AFn() {
            @Override
            public Object invoke() {
                IFn s = ready(c);
                if (s == null) more(g); else s.invoke();
                return null;
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                c.done = true;
                IFn s = ready(c);
                if (s == null) more(g);
                return null;
            }
        });
        c.type = type;
        swap(g, c);
        g.choice = c;
        if (null == ready(c)) more(g);
    }

    static void swap(Gather g, IFn cancel) {
        if (g.choice == null) Util.swap(g, Gather.TOKEN, cancel);
        else {
            Util.swap(g.choice, Choice.TOKEN, cancel);
            if (g.choice.type == Choice.SWITCH) for(;;) {
                IFn current = g.choice.ready;
                if (current == null) {
                    if (!g.choice.done) cancel.invoke();
                    break;
                }
                if (Choice.READY.compareAndSet(g.choice, current, cancel)) break;
            }
        }
    }

    static Gather gather(Process p) {
        Gather g = new Gather();
        g.process = p;
        g.resume = new AFn() {
            @Override
            public Object invoke(Object x) {
                g.current = x;
                if (0 == Gather.PRESSURE.incrementAndGet(g)) step(g);
                return null;
            }
        };
        g.rethrow = new AFn() {
            @Override
            public Object invoke(Object x) {
                g.failed = true;
                return g.resume.invoke(x);
            }
        };
        Object a;
        while (!Process.ALIVE.compareAndSet(p, a = p.alive, a instanceof Integer ?
                ((Integer) a) + 1 : ((IPersistentSet) a).cons(g)));
        if (a instanceof Integer) cancel(g);
        return g;
    }

    static void cancel(Gather g) {
        IFn t;
        while ((t = g.token) != null && !Gather.TOKEN.compareAndSet(g, t, null));
        if (t != null) t.invoke();
    }

    static Process process(IFn c, IFn n, IFn t) {
        Process p = new Process();
        p.notifier = n;
        p.terminator = t;
        RUN.invoke(c, gather(p), null);
        return p;
    }

    static Gather reverse(Gather prev) {
        Gather next = null;
        while (prev != null) {
            Gather swap = prev.next;
            prev.next = next;
            next = prev;
            prev = swap;
        }
        return next;
    }

    static Gather emitter(Gather next) {
        while (next != null && next.choice != null && next.choice.type == Choice.GATHER) {
            Gather g = next;
            next = g.next;
            if (null == ready(g.choice)) more(g);
        }
        return next;
    }

    IFn RUN = new AFn() {
        @Override
        public Object invoke(Object c, Object t, Object x) {
            Gather f = (Gather) t;
            f.coroutine = (IFn) c;
            return f.resume.invoke(x);
        }
    };

    final class Process extends AFn implements IDeref {

        static final AtomicReferenceFieldUpdater<Process, Object> QUEUE =
                AtomicReferenceFieldUpdater.newUpdater(Process.class, Object.class, "queue");

        static final AtomicReferenceFieldUpdater<Process, Object> ALIVE =
                AtomicReferenceFieldUpdater.newUpdater(Process.class, Object.class, "alive");

        volatile Object queue = QUEUE;
        volatile Object alive = PersistentHashSet.EMPTY;

        IFn notifier;
        IFn terminator;
        Gather head;

        @Override
        public Object invoke() {
            for(;;) {
                Object a = alive;
                if (a instanceof Integer) return null;
                if (ALIVE.compareAndSet(this, a, ((IPersistentSet) a).count())) {
                    for (Object o : (Iterable) a) cancel((Gather) o);
                    return null;
                }
            }
        }

        @Override
        public Object deref() {
            try {
                return head.unpark();
            } catch (Throwable e) {
                notifier = new AFn() {
                    @Override
                    public Object invoke() {
                        try {deref();} catch (Throwable _) {}
                        return null;
                    }
                };
                invoke();
                throw e;
            } finally {
                Gather next = emitter(head.next);
                if (head.choice == null) terminate(head);
                else if (null == ready(head.choice)) more(head);
                if (next == null) {
                    Gather prev;
                    while (!QUEUE.compareAndSet(this, prev = (Gather) queue, prev == null ? QUEUE : null)
                            || ((next = emitter(reverse(prev))) == null && prev != null));
                }
                if (next != null) {
                    head = next;
                    notifier.invoke();
                }
            }
        }
    }

    final class Gather implements Fiber {

        static final AtomicReferenceFieldUpdater<Gather, IFn> TOKEN =
                AtomicReferenceFieldUpdater.newUpdater(Gather.class, IFn.class, "token");

        static final AtomicIntegerFieldUpdater<Gather> PRESSURE =
                AtomicIntegerFieldUpdater.newUpdater(Gather.class, "pressure");

        IFn resume;
        IFn rethrow;

        volatile IFn token = NOP;
        volatile int pressure = -1;

        Process process;
        IFn coroutine;
        boolean failed;
        Object current;
        Choice choice;
        Gather next;

        @Override
        public Object poll() {
            if (choice == null ? token == null :
                    choice.token == null || (choice.type == Choice.SWITCH && choice.ready == null && !choice.done))
                throw new ExceptionInfo("Process cancelled.", RT.map(
                        Keyword.intern(null, "cancelled"), Keyword.intern("missionary", "ap")));
            return null;
        }

        @Override
        public Object task(IFn t) {
            swap(this, (IFn) t.invoke(resume, rethrow));
            return CURRENT;
        }

        @Override
        public Object flowConcat(IFn f) {
            choice(this, f, Choice.CONCAT);
            return CURRENT;
        }

        @Override
        public Object flowSwitch(IFn f) {
            choice(this, f, Choice.SWITCH);
            return CURRENT;
        }

        @Override
        public Object flowGather(IFn f) {
            choice(this, f, Choice.GATHER);
            return CURRENT;
        }

        @Override
        public Object unpark() {
            boolean t = failed;
            failed = false;
            Object x = current;
            current = null;
            return t ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
        }
    }

    final class Choice extends AFn {
        static final int CONCAT = 0;
        static final int SWITCH = 1;
        static final int GATHER = 2;

        static final AtomicReferenceFieldUpdater<Choice, IFn> TOKEN =
                AtomicReferenceFieldUpdater.newUpdater(Choice.class, IFn.class, "token");

        static final AtomicReferenceFieldUpdater<Choice, IFn> READY =
                AtomicReferenceFieldUpdater.newUpdater(Choice.class, IFn.class, "ready");

        volatile IFn token = NOP;
        volatile IFn ready = NOP;

        Choice parent;
        IFn backtrack;
        Object iterator;
        int type;
        boolean done;

        @Override
        public Object invoke() {
            for (;;) {
                IFn c = token;
                if (c == null) break;
                if (TOKEN.compareAndSet(this, c, null)) {
                    ((IFn) iterator).invoke();
                    c.invoke();
                    break;
                }
            }
            return null;
        }
    }

}
