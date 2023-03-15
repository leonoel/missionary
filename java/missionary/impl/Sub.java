package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import missionary.Cancelled;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public interface Sub {

    final class Process extends AFn implements IDeref, Subscriber<Object> {

        static {
            Util.printDefault(Process.class);
        }

        IFn terminator;
        IFn notifier;
        Subscription sub;
        Object current;
        Object result;

        @Override
        public Object invoke() {
            cancel(this);
            return null;
        }

        @Override
        public Object deref() {
            return transfer(this);
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (s == null) throw new NullPointerException();
            subscribe(this, s);
        }

        @Override
        public void onNext(Object x) {
            if (x == null) throw new NullPointerException();
            next(this, x);
        }

        @Override
        public void onError(Throwable e) {
            if (e == null) throw new NullPointerException();
            error(this, e);
        }

        @Override
        public void onComplete() {
            complete(this);
        }
    }

    static void cancel(Process ps) {
        Subscription sub;
        Object current;
        Object result;
        synchronized (ps) {
            sub = ps.sub;
            result = ps.result;
            current = ps.current;
            if (result == null) ps.result = new Cancelled("Subscription cancelled.");
        }
        if (result == null) {
            if (sub != null) sub.cancel();
            if (current == null) ps.notifier.invoke();
        }
    }

    static Object transfer(Process ps) {
        Object result;
        Object current;
        synchronized (ps) {
            result = ps.result;
            current = ps.current;
            ps.current = null;
        }
        if (current == null) {
            ps.terminator.invoke();
            return clojure.lang.Util.sneakyThrow((Throwable) result);
        } else {
            if (result == null) ps.sub.request(1);
            else (result == ps ? ps.terminator : ps.notifier).invoke();
            return current;
        }
    }

    static void subscribe(Process ps, Subscription sub) {
        Object result;
        Subscription prev;
        synchronized (ps) {
            prev = ps.sub;
            result = ps.result;
            if (prev == null && result == null) ps.sub = sub;
        }
        if (prev == null && result == null) sub.request(1);
        else sub.cancel();
    }

    static void next(Process ps, Object x) {
        Object result;
        synchronized (ps) {
            result = ps.result;
            if (result == null) ps.current = x;
        }
        if (result == null) ps.notifier.invoke();
    }

    static void error(Process ps, Throwable err) {
        Object current;
        Object result;
        synchronized (ps) {
            current = ps.current;
            result = ps.result;
            if (result == null) ps.result = err;
        }
        if (result == null && current == null) ps.notifier.invoke();
    }

    static void complete(Process ps) {
        Object current;
        Object result;
        synchronized (ps) {
            current = ps.current;
            result = ps.result;
            if (result == null) ps.result = ps;
        }
        if (result == null) (current == null ? ps.terminator : ps.notifier).invoke();
    }

    static Process run(Publisher<?> p, IFn n, IFn t) {
        Process ps = new Process();
        ps.notifier = n;
        ps.terminator = t;
        p.subscribe(ps);
        return ps;
    }
}
