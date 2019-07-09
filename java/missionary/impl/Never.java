package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class Never extends AFn {
    static final AtomicReferenceFieldUpdater<Never, IFn> FAILURE =
            AtomicReferenceFieldUpdater.newUpdater(Never.class, IFn.class, "failure");

    volatile IFn failure;

    public Never(IFn f) {
        failure = f;
    }

    @Override
    public Object invoke() {
        IFn f = failure;
        if (f != null && FAILURE.compareAndSet(this, f, null))
            f.invoke(new ExceptionInfo("Never cancelled.", RT.map(
                    Keyword.intern(null, "cancelled"),
                    Keyword.intern("missionary", "never"))));
        return null;
    }
}
