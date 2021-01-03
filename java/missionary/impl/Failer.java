package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;

public class Failer extends AFn implements IDeref {

    IFn terminator;
    Throwable error;

    public Failer(IFn n, IFn t, Throwable e) {
        terminator = t;
        error = e;
        n.invoke();
    }

    @Override
    public Object invoke() {
        return null;
    }

    @Override
    public Object deref() {
        terminator.invoke();
        return clojure.lang.Util.sneakyThrow(error);
    }
}
