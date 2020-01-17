package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static missionary.impl.Util.NOP;

public final class Sequential extends AFn implements Fiber {

    static final AtomicReferenceFieldUpdater<Sequential, IFn> TOKEN =
            AtomicReferenceFieldUpdater.newUpdater(Sequential.class, IFn.class, "token");

    static final AtomicIntegerFieldUpdater<Sequential> PRESSURE =
            AtomicIntegerFieldUpdater.newUpdater(Sequential.class, "pressure");

    volatile IFn token = NOP;
    volatile int pressure;

    IFn success;
    IFn failure;
    IFn coroutine;

    boolean failed;
    Object current;

    IFn resume = new AFn() {
        @Override
        public Object invoke(Object x) {
            current = x;
            if (0 == PRESSURE.incrementAndGet(Sequential.this)) step();
            return null;
        }
    };

    IFn rethrow = new AFn() {
        @Override
        public Object invoke(Object x) {
            failed = true;
            return resume.invoke(x);
        }
    };

    void step() {
        Fiber prev = CURRENT.get();
        CURRENT.set(this);
        do try {
            Object x = coroutine.invoke();
            if (x != CURRENT) success.invoke(x);
        } catch (Throwable e) {
            failure.invoke(e);
        } while (0 == PRESSURE.decrementAndGet(this));
        CURRENT.set(prev);
    }

    public Sequential(IFn c, IFn s, IFn f) {
        success = s;
        failure = f;
        coroutine = c;
        step();
    }

    @Override
    public Object invoke() {
        for(;;) {
            IFn t = token;
            if (t == null) break;
            if (TOKEN.compareAndSet(this, t, null)) {
                t.invoke();
                break;
            }
        }
        return null;
    }

    @Override
    public Object poll() {
        if (token == null) throw new ExceptionInfo("Process cancelled.", RT.map(
                Keyword.intern(null, "cancelled"), Keyword.intern("missionary", "sp")));
        return null;
    }

    @Override
    public Object task(IFn t) {
        Util.swap(this, TOKEN, (IFn) t.invoke(resume, rethrow));
        return CURRENT;
    }

    @Override
    public Object flowConcat(IFn f) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object flowSwitch(IFn f) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object flowGather(IFn f) {
        throw new UnsupportedOperationException();
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
