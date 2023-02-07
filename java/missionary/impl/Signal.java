package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import missionary.Cancelled;

public interface Signal {
    class Pub extends AFn implements IDeref {
        IFn flow;
        Object process;
        int alive;
        boolean done;
        boolean failed;
        Object value;

        @Override
        public Object invoke() {
            Propagator.dispatch(false, Propagator.none);
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

    static Sub sub(Pub pub) {
        if (pub.process == null) {
            pub.process = pub.flow.invoke(Propagator.bind(new AFn() {
                @Override
                public Object invoke() {
                    if (pub.value == pub) pub.value = null; else {
                        pub.value = pub;
                        Propagator.schedule();
                    }
                    return null;
                }
            }), Propagator.bind(new AFn() {
                @Override
                public Object invoke() {
                    pub.done = true;
                    Propagator.dispatch(true, Propagator.none);
                    return null;
                }
            }));
            if (pub.value == pub) {} // TODO undefined continuous flow
            else pub.value = pub;
        }
        Propagator.attach();
        Propagator.detach(false, Propagator.none);
        pub.alive++;
        Sub s = new Sub();
        s.pub = pub;
        return s;
    }

    static void unsub(Sub sub) {
        Pub pub = sub.pub;
        if (pub != null) {
            int alive = pub.alive;
            if (1 == alive) {
                IFn flow = pub.flow;
                if (flow != null) {
                    pub.flow = null;
                    Propagator.reset(make(flow));
                    ((IFn) pub.process).invoke();
                }
            } else {
                sub.pub = null;
                pub.alive = alive - 1;
                if (Propagator.attached()) Propagator.detach(false, Propagator.none);
            }
        }
    }

    static Object transfer(Sub sub) {
        Pub pub = sub.pub;
        if (pub == null) {
            Propagator.attach();
            Propagator.detach(true, Propagator.none);
            return clojure.lang.Util.sneakyThrow(new Cancelled("Signal subscription cancelled."));
        } else {
            Object x = pub.value;
            if (x == pub) {
                try {
                    for(;;) {
                        x = ((IDeref) pub.process).deref();
                        if (pub.value == pub) break;
                        else pub.value = pub;
                    }
                } catch (Throwable e) {
                    pub.failed = true;
                    x = e;
                }
                pub.value = x;
            }
            Propagator.attach();
            if (pub.done) Propagator.detach(true, Propagator.none);
            return pub.failed ? clojure.lang.Util.sneakyThrow((Throwable) x) : x;
        }
    }

    static Pub make(IFn flow) {
        Pub p = new Pub();
        p.flow = flow;
        p.value = p;
        return p;
    }
}