package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.RT;

import java.util.Iterator;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public interface Zip {

    final class Process extends AFn implements IDeref {

        static {
            Util.printDefault(Process.class);
        }

        Lock lock;
        IFn combine;
        IFn step;
        IFn done;
        Object[] inputs;
        int pending;

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

    static void ready(Process ps) {
        IFn s = ps.step;
        if (s == null) {
            int p, c = 0;
            ps.lock.lock();
            for (Object input : ps.inputs) if (input != ps) try {
                c++;
                ((IDeref) input).deref();
            } catch (Throwable e) {}
            p = ps.pending += c;
            ps.lock.unlock();
            if (0 == c) ps.done.invoke();
            else if (0 == p) ready(ps);
        } else s.invoke();
    }

    static void cancel(Process ps) {
        for (Object input: ps.inputs) if (input != ps) ((IFn) input).invoke();
    }

    static Object transfer(Process ps) {
        int c = 0;
        Object[] inputs = ps.inputs;
        int arity = inputs.length;
        Object[] buffer = new Object[arity];
        ps.lock.lock();
        try {
            for (int i = 0; i < arity; i++) {
                c++;
                buffer[i] = ((IDeref) inputs[i]).deref();
            }
            return Util.apply(ps.combine, buffer);
        } catch (Throwable e) {
            ps.step = null;
            throw e;
        } finally {
            int p = ps.pending += c;
            ps.lock.unlock();
            if (ps.step == null) cancel(ps);
            if (0 == p) ready(ps);
        }
    }

    static Process run(IFn f, Object fs, IFn s, IFn d) {
        int arity = RT.count(fs);
        Object[] inputs = new Object[arity];
        Lock lock = new ReentrantLock();
        Process ps = new Process();
        ps.lock = lock;
        ps.combine = f;
        ps.step = s;
        ps.done = d;
        ps.inputs = inputs;
        Iterator it = RT.iter(fs);
        int i = 0;
        lock.lock();
        do {
            int index = i++;
            Object input = ((IFn) it.next()).invoke(
                    new AFn() {
                        @Override
                        public Object invoke() {
                            ps.lock.lock();
                            int p = --ps.pending;
                            ps.lock.unlock();
                            if (0 == p) ready(ps);
                            return null;
                        }
                    },
                    new AFn() {
                        @Override
                        public Object invoke() {
                            ps.lock.lock();
                            ps.inputs[index] = ps;
                            ps.step = null;
                            int p = --ps.pending;
                            ps.lock.unlock();
                            if (0 <= p) cancel(ps);
                            if (0 == p) ready(ps);
                            return null;
                        }
                    }
            );
            if (inputs[index] == null)
                inputs[index] = input;
        } while (it.hasNext());
        int p = ps.pending += arity;
        lock.unlock();
        if (ps.step == null) cancel(ps);
        if (0 == p) ready(ps);
        return ps;
    }

}
