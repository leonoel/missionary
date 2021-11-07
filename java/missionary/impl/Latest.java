package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.RT;

import java.util.Iterator;

public interface Latest {

    class Process extends AFn implements IDeref {

        static {
            Util.printDefault(Process.class);
        }

        IFn combinator;
        IFn notifier;
        IFn terminator;
        Object[] args;
        Object[] inputs;
        boolean race;
        int alive;

        @Override
        public Object invoke() {
            for (Object it : inputs)
                ((IFn) it).invoke();
            return null;
        }

        @Override
        public synchronized Object deref() {
            return transfer(this);
        }
    }

    static Object transfer(Process p) {
        IFn c = p.combinator;
        Object[] args = p.args;
        Object[] inputs = p.inputs;
        int arity = inputs.length;
        try {
            if (c == null) throw new Error("Undefined continuous flow.");
            for (int i = 0; i != arity; i++) if (args[i] == args) {
                IDeref input = (IDeref) inputs[i];
                Object x;
                do {
                    args[i] = null;
                    x = input.deref();
                } while (args[i] == args);
                args[i] = x;
            }
            return Util.apply(c, args);
        } catch (Throwable e) {
            for (int i = 0; i != arity; i++) {
                Object it = inputs[i];
                ((IFn) it).invoke();
                if (args[i] == args) Util.discard(it);
                else args[i] = args;
            }
            throw e;
        } finally {
            p.race = true;
            if (p.alive == 0)
                p.terminator.invoke();
        }
    }

    static void dirty(Process p, int i) {
        Object[] args = p.args;
        if (args[i] == args) Util.discard(p.inputs[i]);
        else {
            args[i] = args;
            if (p.race) {
                p.race = false;
                p.notifier.invoke();
            }
        }
    }

    static void terminate(Process p) {
        if (--p.alive == 0 && p.race)
            p.terminator.invoke();
    }

    static Object run(Object c, Object fs, Object n, Object t) {
        int arity = RT.count(fs);
        Object[] args = new Object[arity];
        Object[] inputs = new Object[arity];
        Process process = new Process();
        (process.notifier = (IFn) n).invoke();
        process.terminator = (IFn) t;
        process.combinator = (IFn) c;
        process.alive = arity;
        process.args = args;
        process.inputs = inputs;
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
            Iterator flows = RT.iter(fs);
            while (flows.hasNext()) {
                int index = i;
                inputs[i] = ((IFn) flows.next()).invoke(new AFn() {
                    @Override
                    public Object invoke() {
                        synchronized (process) {
                            dirty(process, index);
                            return null;
                        }
                    }
                }, done);
                if (args[i] == null) process.combinator = null;
                i++;
            }
        }
        return process;
    }
}