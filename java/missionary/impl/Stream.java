package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import missionary.Cancelled;

public interface Stream {
    class Pub extends AFn implements IDeref {
        IFn flow;
        Object process;
        boolean ready;
        boolean failed;
        boolean done;
        Object value;
        int attached;
        int pending;
        Object thread;
        long step;

        @Override
        public Object invoke() {
            Stream.run(this);
            return null;
        }

        @Override
        public Object deref() {
            return sub(this);
        }
    }

    class Sub extends AFn implements IDeref {
        Pub pub;

        @Override
        public Object invoke() {
            unsub(this);
            return null;
        }

        @Override
        public Object deref() {
            return transfer(this);
        }
    }

    static void emit(Pub pub) {
        pub.pending = pub.attached;
        pub.attached = 0;
        pub.value = pub;
        pub.ready = false;
        pub.thread = Thread.currentThread();
        pub.step = Propagator.step();
        Propagator.dispatch(false, Propagator.none);
    }

    static void run(Pub pub) {
        pub.ready = true;
        if (pub.pending == 0) emit(pub);
    }

    static Sub sub(Pub pub) {
        if (pub.process == null) pub.process = pub.flow.invoke(Propagator.bind(new AFn() {
            @Override
            public Object invoke() {
                if (pub.process == null) emit(pub);
                else Propagator.schedule();
                return null;
            }
        }), Propagator.bind(new AFn() {
            @Override
            public Object invoke() {
                pub.done = true;
                pub.attached = 0;
                Propagator.dispatch(true, Propagator.none);
                return null;
            }
        }));
        Propagator.attach();
        if (pub.thread == Thread.currentThread() && pub.step == Propagator.step()) {
            pub.pending++;
            Propagator.detach(false, Propagator.none);
        } else pub.attached++;
        Sub sub = new Sub();
        sub.pub = pub;
        return sub;
    }

    static void unsub(Sub sub) {
        Pub pub = sub.pub;
        if (pub != null && !pub.done) {
            int attached = pub.attached;
            int pending = pub.pending;
            if (1 == attached + pending) {
                IFn flow = pub.flow;
                if (flow != null) {
                    pub.flow = null;
                    Propagator.reset(make(flow));
                    ((IFn) pub.process).invoke();
                }
            } else {
                sub.pub = null;
                if (Propagator.attached()) {
                    pub.attached = attached - 1;
                    Propagator.detach(false, Propagator.none);
                } else {
                    pub.pending = pending - 1;
                    if (1 == pending && pub.ready) emit(pub);
                }
            }
        }
    }

    static Object transfer(Sub sub) {
        Pub pub = sub.pub;
        if (pub == null) {
            Propagator.attach();
            Propagator.detach(true, Propagator.none);
            return clojure.lang.Util.sneakyThrow(new Cancelled("Stream subscription cancelled."));
        } else {
            Object x = pub.value;
            if (x == pub) {
                try {
                    x = ((IDeref) pub.process).deref();
                } catch (Throwable e) {
                    pub.failed = true;
                    x = e;
                }
                pub.value = x;
            }
            Propagator.attach();
            if (pub.done) Propagator.detach(true, Propagator.none); else {
                pub.attached++;
                if (0 == --pub.pending && pub.ready) emit(pub);
            }
            return pub.failed ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
        }
    }

    static Pub make(IFn flow) {
        Pub p = new Pub();
        p.flow = flow;
        p.step = -1;
        return p;
    }
}