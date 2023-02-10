package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public interface Never {
    AtomicReferenceFieldUpdater<Process, IFn> FAILURE =
            AtomicReferenceFieldUpdater.newUpdater(Process.class, IFn.class, "failure");

    final class Process extends AFn {
        static {
            Util.printDefault(Process.class);
        }

        volatile IFn failure;

        @Override
        public Object invoke() {
            cancel(this);
            return null;
        }
    }

    static void cancel(Process ps) {
        IFn f = ps.failure;
        if (f != null && FAILURE.compareAndSet(ps, f, null))
            f.invoke(new Cancelled("Never cancelled."));
    }

    static Process run(IFn f) {
        Process ps = new Process();
        ps.failure = f;
        return ps;
    }

}