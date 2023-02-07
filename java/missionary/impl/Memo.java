package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import missionary.Cancelled;

public interface Memo {
    class Pub extends AFn implements IDeref {
        IFn task;
        Object process;
        boolean failed;
        Object result;
        int alive;

        @Override
        public Object invoke() {
            Memo.run(this);
            return null;
        }

        @Override
        public Object deref() {
            return sub(this);
        }
    }

    class Sub extends AFn {
        Pub pub;

        @Override
        public Object invoke() {
            unsub(this);
            return null;
        }
    }

    static void run(Pub pub) {
        pub.alive = 0;
        Propagator.dispatch(pub.failed, pub.result);
    }

    static Sub sub(Pub pub) {
        if (pub.process == null) pub.process = pub.task.invoke(Propagator.bind(new AFn() {
            @Override
            public Object invoke(Object x) {
                pub.result = x;
                if (pub.process != null) Propagator.schedule();
                return null;
            }
        }), Propagator.bind(new AFn() {
            @Override
            public Object invoke(Object x) {
                pub.failed = true;
                pub.result = x;
                if (pub.process != null) Propagator.schedule();
                return null;
            }
        }));
        Propagator.attach();
        if (pub.result == pub) pub.alive++;
        else Propagator.detach(pub.failed, pub.result);
        Sub s = new Sub();
        s.pub = pub;
        return s;
    }

    static void unsub(Sub sub) {
        Pub pub = sub.pub;
        if (pub != null) {
            sub.pub = null;
            if (pub.result == pub) {
                int alive = pub.alive;
                if (1 == alive) {
                    Propagator.reset(make(pub.task));
                    ((IFn) pub.process).invoke();
                } else {
                    pub.alive = alive - 1;
                    Propagator.detach(true, new Cancelled("Memo subscription cancelled."));
                }
            }
        }
    }

    static Pub make(IFn task) {
        Pub p = new Pub();
        p.task = task;
        p.result = p;
        return p;
    }
}