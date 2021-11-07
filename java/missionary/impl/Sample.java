package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.RT;

import java.util.Iterator;

public interface Sample {

    class Process extends AFn implements IDeref {

        static {
            Util.printDefault(Process.class);
        }

        IFn combinator;
        IFn notifier;
        IFn terminator;
        Object[] args;
        Object[] inputs;
        boolean busy;
        boolean done;
        int alive;

        @Override
        public Object invoke() {
            return ((IFn) inputs[inputs.length - 1]).invoke();
        }

        @Override
        public synchronized Object deref() {
            return transfer(this);
        }
    }

    static void terminate(Process p) {
        if (--p.alive == 0) p.terminator.invoke();
    }

    static void ready(Process p) {
        Object[] args = p.args;
        Object[] inputs = p.inputs;
        int sampled = inputs.length - 1;
        while (p.busy = !p.busy)
            if (p.done) {
                for (int i = 0; i != sampled; i++) {
                    Object input = inputs[i];
                    ((IFn) input).invoke();
                    if (args[i] == args) Util.discard(input);
                    else args[i] = args;
                }
                terminate(p);
                break;
            }
            else if (args[sampled] == args)
                Util.discard(inputs[sampled]);
            else {
                p.notifier.invoke();
                break;
            }
    }

    static Object transfer(Process p) {
        IFn c = p.combinator;
        Object[] args = p.args;
        Object[] inputs = p.inputs;
        int sampled = inputs.length - 1;
        Object sampler = inputs[sampled];
        try {
            try {
                if (c == null) throw new Error("Undefined continuous flow.");
                for (int i = 0; i != sampled; i++) if (args[i] == args) {
                    IDeref input = (IDeref) inputs[i];
                    Object x;
                    do {
                        args[i] = null;
                        x = input.deref();
                    } while (args[i] == args);
                    args[i] = x;
                }
            } catch (Throwable e) {
                Util.discard(sampler);
                throw e;
            }
            args[sampled] = ((IDeref) sampler).deref();
            return Util.apply(c, args);
        } catch (Throwable e) {
            ((IFn) sampler).invoke();
            args[sampled] = args;
            throw e;
        } finally {
            ready(p);
        }
    }

    static void dirty(Process p, int i) {
        Object[] args = p.args;
        if (args[i] == args) Util.discard(p.inputs[i]);
        else args[i] = args;
    }

    static Object run(Object c, Object f, Object fs, Object n, Object t) {
        int arity = RT.count(fs) + 1;
        Object[] inputs = new Object[arity];
        Object[] args = new Object[arity];
        Process process = new Process();
        process.combinator = (IFn) c;
        process.notifier = (IFn) n;
        process.terminator = (IFn) t;
        process.inputs = inputs;
        process.args = args;
        process.alive = arity;
        IFn done = new AFn() {
            @Override
            public Object invoke() {
                synchronized (process) {
                    terminate(process);
                    return null;
                }
            }
        };
        synchronized (process) {
            int i = 0;
            IFn prev = (IFn) f;
            Iterator flows = RT.iter(fs);
            while (flows.hasNext()) {
                int index = i;
                inputs[i] = prev.invoke(new AFn() {
                    @Override
                    public Object invoke() {
                        synchronized (process) {
                            dirty(process, index);
                            return null;
                        }
                    }
                }, done);
                if (args[i] == null) process.combinator = null;
                prev = (IFn) flows.next();
                i++;
            }
            inputs[i] = prev.invoke(new AFn() {
                @Override
                public Object invoke() {
                    synchronized (process) {
                        ready(process);
                        return null;
                    }
                }
            }, new AFn() {
                @Override
                public Object invoke() {
                    synchronized(process) {
                        process.done = true;
                        ready(process);
                        return null;
                    }
                }
            });
        }
        return process;
    }

}