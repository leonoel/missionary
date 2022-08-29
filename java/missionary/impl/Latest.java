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
        Object value;
        Object[] args;
        Object[] inputs;
        int[] dirty;
        int alive;

        @Override
        public Object invoke() {
            kill(this);
            return null;
        }

        @Override
        public Object deref() {
            return transfer(this);
        }
    }

    static void kill(Process ps) {
        for (Object it : ps.inputs)
            ((IFn) it).invoke();
    }

    static Object transfer(Process ps) {
        IFn c = ps.combinator;
        Object[] args = ps.args;
        Object[] inputs = ps.inputs;
        int[] dirty = ps.dirty;
        Object x = ps.value;
        synchronized (ps) {
            try {
                ps.value = ps;
                if (args == null) throw new Error("Undefined continuous flow.");
                boolean pass = x != ps;
                do {
                    int i = Heap.dequeue(dirty);
                    Object p = args[i];
                    args[i] = ((IDeref) inputs[i]).deref();
                    if (pass) pass = clojure.lang.Util.equiv(p, args[i]);
                } while (0 < Heap.size(dirty));
                if (!pass) x = Util.apply(c, args);
            } catch (Throwable e) {
                kill(ps);
                while (0 < Heap.size(dirty)) try {
                    ((IDeref) inputs[Heap.dequeue(dirty)]).deref();
                } catch (Throwable f) {}
                ps.notifier = null;
                x = e;
            }
            ps.value = x;
        }
        if (ps.alive == 0) ps.terminator.invoke();
        return ps.notifier == null ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
    }

    static Process run(IFn c, Object fs, IFn n, IFn t) {
        int arity = RT.count(fs);
        Iterator it = RT.iter(fs);
        Object[] args = new Object[arity];
        Object[] inputs = new Object[arity];
        int[] dirty = Heap.create(arity);
        Process ps = new Process();
        ps.notifier = n;
        ps.terminator = t;
        ps.combinator = c;
        ps.value = ps;
        ps.alive = arity;
        ps.inputs = inputs;
        ps.dirty = dirty;
        IFn done = new AFn() {
            @Override
            public Object invoke() {
                boolean last;
                synchronized (ps) {
                    last = --ps.alive == 0 && ps.value != ps;
                }
                if (last) ps.terminator.invoke();
                return null;
            }
        };
        synchronized (ps) {
            for(int i = 0; i < arity; i++) {
                int index = i;
                inputs[i] = ((IFn) it.next()).invoke(new AFn() {
                    @Override
                    public Object invoke() {
                        boolean race;
                        synchronized (ps) {
                            Heap.enqueue(dirty, index);
                            race = Heap.size(dirty) == 1 && ps.value != ps;
                        }
                        if (race) {
                            IFn n = ps.notifier;
                            if (n == null) synchronized (ps) {
                                do try {
                                    ((IDeref) inputs[Heap.dequeue(dirty)]).deref();
                                } catch (Throwable f) {} while (0 < Heap.size(dirty));
                            } else n.invoke();
                        }
                        return null;
                    }
                }, done);
            }
        }
        if (Heap.size(dirty) == arity) ps.args = args;
        n.invoke();
        return ps;
    }
}