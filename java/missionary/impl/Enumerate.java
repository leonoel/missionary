package missionary.impl;

import clojure.lang.*;

import java.util.Iterator;

public final class Enumerate extends AFn implements IDeref {
    IFn notifier;
    IFn terminator;
    volatile Iterator iterator;

    // TODO handle exceptions thrown by iterator

    void more(Iterator i) {
        if (i.hasNext()) notifier.invoke();
        else {
            iterator = null;
            terminator.invoke();
        }
    }

    public Enumerate(Object coll, IFn n, IFn t) {
        Iterator i = RT.iter(coll);
        notifier = n;
        terminator = t;
        iterator = i;
        more(i);
    }

    @Override
    public Object invoke() {
        iterator = null;
        return null;
    }

    @Override
    public Object deref() {
        Iterator i = iterator;
        if (i == null) {
            terminator.invoke();
            throw new ExceptionInfo("Enumeration cancelled.", RT.map(
                    Keyword.intern(null, "cancelled"),
                    Keyword.intern("missionary", "enumerate")));
        }
        Object x = i.next();
        more(i);
        return x;
    }
}
