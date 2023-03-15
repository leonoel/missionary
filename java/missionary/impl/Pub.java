package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public interface Pub {
    class Process implements Subscription {
        static {
            Util.printDefault(Process.class);
        }

        Subscriber<Object> subscriber;
        Object iterator;
        Object current;
        long requested;
        boolean busy;
        boolean done;

        @Override
        public void request(long n) {
            if (0 < n) more(this, n);
            else kill(this, new IllegalArgumentException("Negative subscription request (3.9)"));
        }

        @Override
        public void cancel() {
            kill(this, null);
        }

    }

    static void kill(Process ps, Throwable e) {
        long requested;
        Subscriber<Object> sub;
        synchronized (ps) {
            requested = ps.requested;
            sub = ps.subscriber;
            ps.subscriber = null;
            if (requested < 0) ps.current = null;
        }
        if (sub != null) {
            ((IFn) ps.iterator).invoke();
            if (requested < 0) ready(ps);
            if (e != null) sub.onError(e);
        }
    }

    static void more(Process ps, long n) {
        long requested;
        Object current;
        Subscriber<Object> sub;
        synchronized (ps) {
            sub = ps.subscriber;
            current = ps.current;
            requested = ps.requested;
            long r = requested + n;
            ps.requested = r < 0 ? Long.MAX_VALUE : r;
            if (sub != null && requested < 0) ps.current = null;
        }
        if (sub != null && requested < 0) {
            sub.onNext(current);
            ready(ps);
        }
    }

    static void ready(Process ps) {
        Subscriber<Object> sub;
        for(;;) {
            boolean terminated = false;
            Object current = null;
            synchronized (ps) {
                sub = ps.subscriber;
                if (ps.busy = !ps.busy) if (sub == null) if (ps.done);
                else Util.discard(ps.iterator);
                else if (ps.done) {
                    terminated = true;
                    current = ps.current;
                    ps.current = null;
                    ps.subscriber = null;
                } else try {
                    current = ((IDeref) ps.iterator).deref();
                    if (0 == ps.requested--) {
                        ps.current = current;
                        break;
                    }
                } catch (Throwable e) {
                    ps.requested = Long.MAX_VALUE;
                    ps.current = e;
                } else break;
            }
            if (terminated) if (current == null) sub.onComplete();
            else sub.onError((Throwable) current);
            else if (current == null);
            else sub.onNext(current);
        }
    }

    static void run(IFn f, Subscriber<Object> s) {
        Process ps = new Process();
        ps.busy = true;
        ps.subscriber = s;
        ps.iterator = f.invoke(new AFn() {
            @Override
            public Object invoke() {
                ready(ps);
                return null;
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                ps.done = true;
                ready(ps);
                return null;
            }
        });
        s.onSubscribe(ps);
        ready(ps);
    }

}
