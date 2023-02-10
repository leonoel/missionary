package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import java.util.Iterator;

public interface Seed {

    final class Process extends AFn implements IDeref {
        static {
            Util.printDefault(Process.class);
        }

        IFn notifier;
        IFn terminator;
        volatile Iterator iterator;

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

    static void cancel(Process ps) {
        ps.iterator = null;
    }

    static Object transfer(Process ps) {
        Iterator i = ps.iterator;
        if (i == null) {
            ps.terminator.invoke();
            clojure.lang.Util.sneakyThrow(new Cancelled("Seed cancelled."));
        }
        Object x = i.next();
        more(ps, i);
        return x;
    }

    // TODO handle exceptions thrown by iterator
    static void more(Process ps, Iterator i) {
        if (i.hasNext()) ps.notifier.invoke();
        else {
            ps.iterator = null;
            ps.terminator.invoke();
        }
    }

    static Process run(Object coll, IFn n, IFn t) {
        Process ps = new Process();
        Iterator i = RT.iter(coll);
        ps.notifier = n;
        ps.terminator = t;
        ps.iterator = i;
        more(ps, i);
        return ps;
    }
}