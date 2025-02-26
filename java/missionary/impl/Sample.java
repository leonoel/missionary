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
            for (Object input : inputs) ((IFn) input).invoke();
            return null;
        }

        @Override
        public Object deref() {
            return transfer(this);
        }
    }

    static IFn ready(Process ps) {
        IFn cb = null;
        Object[] args = ps.args;
        Object[] inputs = ps.inputs;
        int sampled = inputs.length - 1;
        while (ps.busy = !ps.busy) if (ps.done) {
            for (int i = 0; i != sampled; i++) {
                Object input = inputs[i];
                ((IFn) input).invoke();
                if (args[i] == args) Util.discard(input);
                else args[i] = args;
            }
            cb = --ps.alive == 0 ? ps.terminator : null;
            break;
        } else if (args[sampled] == args) Util.discard(inputs[sampled]); else {
            cb = ps.notifier;
            break;
        }
        return cb;
    }

    static Object transfer(Process ps) {
        IFn cb;
        Object x;
        IFn c = ps.combinator;
        Object[] args = ps.args;
        Object[] inputs = ps.inputs;
        int sampled = inputs.length - 1;
        Object sampler = inputs[sampled];
        synchronized (ps) {
            try {
                try {
                    if (c == null) throw new Error("Undefined continuous flow.");
                    for (int i = 0; i != sampled; i++) if (args[i] == args) {
                        IDeref input = (IDeref) inputs[i];
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
                x = Util.apply(c, args);
            } catch (Throwable e) {
                x = e;
                ps.notifier = null;
                ((IFn) sampler).invoke();
                args[sampled] = args;
            }
            cb = ready(ps);
        }
        if (cb != null) cb.invoke();
        return ps.notifier == null ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }

    static Object run(Object c, Object f, Object fs, Object n, Object t) {
        int arity = RT.count(fs) + 1;
        Object[] inputs = new Object[arity];
        Object[] args = new Object[arity];
        Process ps = new Process();
        ps.combinator = (IFn) c;
        ps.notifier = (IFn) n;
        ps.terminator = (IFn) t;
        ps.inputs = inputs;
        ps.args = args;
        ps.alive = arity;
        IFn done = new AFn() {
            @Override
            public Object invoke() {
                boolean last;
                synchronized (ps) {
                    last = --ps.alive == 0;
                }
                return last ? ps.terminator.invoke() : null;
            }
        };
        synchronized (ps) {
            int i = 0;
            IFn prev = (IFn) f;
            Iterator flows = RT.iter(fs);
            while (flows.hasNext()) {
                int index = i;
                inputs[i] = prev.invoke(new AFn() {
                    @Override
                    public Object invoke() {
                        Object[] args = ps.args;
                        synchronized (ps) {
                            if (args[index] == args) Util.discard(ps.inputs[index]);
                            else args[index] = args;
                        }
                        return null;
                    }
                }, done);
                if (args[i] == null) ps.combinator = null;
                prev = (IFn) flows.next();
                i++;
            }
            inputs[i] = prev.invoke(new AFn() {
                @Override
                public Object invoke() {
                    IFn cb;
                    synchronized (ps) {
                        cb = ready(ps);
                    }
                    return cb == null ? null : cb.invoke();
                }
            }, new AFn() {
                @Override
                public Object invoke() {
                    ps.done = true;
                    IFn cb;
                    synchronized (ps) {
                        cb = ready(ps);
                    }
                    return cb == null ? null : cb.invoke();
                }
            });
        }
        return ps;
    }

}