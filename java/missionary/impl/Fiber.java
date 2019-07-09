package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Util.NOP;

public final class Fiber extends AFn implements IDeref {

    static final AtomicReferenceFieldUpdater<Fiber, IFn> TOKEN =
            AtomicReferenceFieldUpdater.newUpdater(Fiber.class, IFn.class, "token");

    static final AtomicIntegerFieldUpdater<Fiber> PRESSURE =
            AtomicIntegerFieldUpdater.newUpdater(Fiber.class, "pressure");

    static final ThreadLocal<Fiber> FIBER = new ThreadLocal<>();

    static final IFn FORK = new AFn() {
        @Override
        public Object invoke(Object c, Object t, Object x) {
            Fiber f = (Fiber) t;
            f.coroutine = (IFn) c;
            return f.success.invoke(x);
        }
    };

    IFn success = new AFn() {
        @Override
        public Object invoke(Object x) {
            current = x;
            if (0 == PRESSURE.incrementAndGet(Fiber.this)) step();
            return null;
        }
    };

    IFn failure = new AFn() {
        @Override
        public Object invoke(Object x) {
            failed = true;
            return success.invoke(x);
        }
    };

    volatile IFn token = NOP;
    volatile int pressure;

    boolean ambiguous;
    IFn coroutine;
    boolean failed;
    Object current;
    Choice choice;
    IFn arg1;
    IFn arg2;

    public Fiber(boolean a, IFn c, IFn a1, IFn a2) {
        ambiguous = a;
        coroutine = c;
        arg1 = a1;
        arg2 = a2;
        step();
    }

    void swap(IFn cancel) {
        if (choice == null) Util.swap(this, TOKEN, cancel);
        else {
            Util.swap(choice, Choice.TOKEN, cancel);
            if (choice.preemptive) for(;;) {
                IFn current = choice.ready;
                if (current == null) {
                    if (!choice.done) cancel.invoke();
                    break;
                }
                if (Choice.READY.compareAndSet(choice, current, cancel)) break;
            }
        }
    }

    void step() {
        Fiber prev = FIBER.get();
        FIBER.set(this);
        do try {
            Object x = coroutine.invoke();
            if (x != FIBER) {
                if (ambiguous) {
                    current = x;
                    arg1.invoke();
                } else arg1.invoke(x);
            }
        } catch (Throwable e) {
            if (ambiguous) {
                failed = true;
                current = e;
                arg1.invoke();
            } else arg2.invoke(e);
        } while (0 == PRESSURE.decrementAndGet(this));
        FIBER.set(prev);
    }

    void more() {
        do if (choice.done) {
            choice = choice.parent;
            if (choice == null) {
                arg2.invoke();
                return;
            }
        } else if (failed) try {
            ((IDeref) choice.iterator).deref();
        } catch (Throwable _) {
        } else try {
            choice.backtrack.invoke(FORK, this, ((IDeref) choice.iterator).deref());
            return;
        } catch (Throwable e) {
            coroutine = choice.backtrack;
            failed = true;
            current = e;
            if (0 == PRESSURE.incrementAndGet(this)) step();
            return;
        } while (null == choice.ready());
    }

    @Override
    public Object invoke() {
        for(;;) {
            IFn cancel = token;
            if (cancel == null) break;
            if (TOKEN.compareAndSet(this, cancel, null)) {
                cancel.invoke();
                break;
            }
        }
        return null;
    }

    @Override
    public Object deref() {
        boolean f = failed;
        Object x = current;
        current = null;
        if (choice == null) arg2.invoke();
        else if (null == choice.ready()) more();
        return f ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }

    public static Object poll() {
        Fiber p = FIBER.get();
        if (p == null ? Thread.currentThread().isInterrupted() :
                p.choice == null ? p.token == null :
                        p.choice.token == null || (p.choice.preemptive && p.choice.ready == null && !p.choice.done))
            throw new ExceptionInfo("Process cancelled.", RT.map(
                    Keyword.intern(null, "cancelled"),
                    Keyword.intern("missionary", p.ambiguous ? "ap" : "sp")));
        else return null;
    }

    public static Object task(IFn t) {
        Fiber f = FIBER.get();
        if (f == null) {
            return new CountDownLatch(1) {
                boolean failed;
                Object result;
                {
                    IFn cancel = (IFn) t.invoke(
                            new AFn() {
                                @Override
                                public Object invoke(Object x) {
                                    failed = false;
                                    result = x;
                                    countDown();
                                    return null;
                                }
                            },
                            new AFn() {
                                @Override
                                public Object invoke(Object x) {
                                    failed = true;
                                    result = x;
                                    countDown();
                                    return null;
                                }
                            });
                    try {await();}
                    catch (InterruptedException _) {
                        cancel.invoke();
                        try {await();} catch (InterruptedException __) {}
                        Thread.currentThread().interrupt();
                    }
                    if (failed) clojure.lang.Util.sneakyThrow((Throwable) result);
                }
            }.result;
        } else {
            f.swap((IFn) t.invoke(f.success, f.failure));
            return FIBER;
        }
    }

    public static Object flow(IFn flow, boolean preemptive) {
        Fiber f = FIBER.get();
        assert f != null;
        assert f.ambiguous;
        Choice c = new Choice();
        IFn notifier = new AFn() {
            @Override
            public Object invoke() {
                IFn s = c.ready();
                if (s == null) f.more(); else s.invoke();
                return null;
            }
        };
        IFn terminator = new AFn() {
            @Override
            public Object invoke() {
                c.done = true;
                IFn s = c.ready();
                if (s == null) f.more();
                return null;
            }
        };
        c.parent = f.choice;
        c.backtrack = f.coroutine;
        c.iterator = flow.invoke(notifier, terminator);
        c.preemptive = preemptive;
        f.swap(c);
        f.choice = c;
        notifier.invoke();
        return FIBER;
    }

    public static Object unpark() {
        Fiber f = FIBER.get();
        assert f != null;
        boolean t = f.failed;
        f.failed = false;
        Object x = f.current;
        f.current = null;
        return t ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }

}
