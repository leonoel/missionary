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
        public Object deref() {
            return transfer(this);
        }
    }

    static Object transfer(Process ps) {
        Object x;
        IFn c = ps.combinator;
        Object[] args = ps.args;
        Object[] inputs = ps.inputs;
        int arity = inputs.length;
        synchronized (ps) {
            try {
                if (c == null) throw new Error("Undefined continuous flow.");
                for (int i = 0; i != arity; i++) if (args[i] == args) {
                    IDeref input = (IDeref) inputs[i];
                    do {
                        args[i] = null;
                        x = input.deref();
                    } while (args[i] == args);
                    args[i] = x;
                }
                x = Util.apply(c, args);
            } catch (Throwable e) {
                x = e;
                ps.notifier = null;
                for (int i = 0; i != arity; i++) {
                    Object it = inputs[i];
                    ((IFn) it).invoke();
                    if (args[i] == args) Util.discard(it);
                    else args[i] = args;
                }
            }
            ps.race = true;
        }
        if (ps.alive == 0) ps.terminator.invoke();
        return ps.notifier == null ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }

    static void dirty(Process ps, int i) {
        Object[] args = ps.args;
        boolean first = false;
        synchronized (ps) {
            if (args[i] == args) Util.discard(ps.inputs[i]); else {
                args[i] = args;
                if (first = ps.race) ps.race = false;
            }
        }
        if (first) ps.notifier.invoke();
    }

    static Object run(Object c, Object fs, Object n, Object t) {
        int arity = RT.count(fs);
        Object[] args = new Object[arity];
        Object[] inputs = new Object[arity];
        Process ps = new Process();
        (ps.notifier = (IFn) n).invoke();
        ps.terminator = (IFn) t;
        ps.combinator = (IFn) c;
        ps.alive = arity;
        ps.args = args;
        ps.inputs = inputs;
        IFn done = new AFn() {
            @Override
            public Object invoke() {
                boolean last;
                synchronized (ps) {
                    last = --ps.alive == 0 && ps.race;
                }
                if (last) ps.terminator.invoke();
                return null;
            }
        };
        synchronized (ps) {
            int i = 0;
            Iterator flows = RT.iter(fs);
            while (flows.hasNext()) {
                int index = i;
                inputs[i] = ((IFn) flows.next()).invoke(new AFn() {
                    @Override
                    public Object invoke() {
                        dirty(ps, index);
                        return null;
                    }
                }, done);
                if (args[i] == null) ps.combinator = null;
                i++;
            }
        }
        return ps;
    }
}