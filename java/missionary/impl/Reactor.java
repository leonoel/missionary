package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.IDeref;
import missionary.Cancelled;

public interface Reactor {

    final class Failer extends AFn implements IDeref {

        static {
            Util.printDefault(Failer.class);
        }

        IFn terminator;
        Throwable error;

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

    final class Subscription extends AFn implements IDeref {

        static {
            Util.printDefault(Subscription.class);
        }

        IFn notifier;
        IFn terminator;
        Publisher subscriber;
        Publisher subscribed;
        Subscription prev;
        Subscription next;

        @Override
        public Object invoke() {
            unsubscribe(this);
            return null;
        }

        @Override
        public Object deref() {
            return push(this);
        }
    }

    final class Publisher extends AFn {

        static {
            Util.printDefault(Publisher.class);
        }

        Process process;
        Object iterator;
        int[] ranks;
        int pending;
        int children;
        boolean live;
        boolean busy;
        boolean done;
        Object value;
        Publisher prev;
        Publisher next;
        Publisher child;
        Publisher sibling;
        Publisher active;
        Subscription subs;

        @Override
        public Object invoke() {
            free(this);
            return null;
        }

        @Override
        public Object invoke(Object n, Object t) {
            return subscribe(this, (IFn) n, (IFn) t);
        }
    }

    final class Process extends AFn {

        static {
            Util.printDefault(Process.class);
        }

        IFn success;
        IFn failure;
        Object error;
        Publisher kill;
        Publisher boot;
        Publisher alive;
        Publisher active;
        Publisher current;
        Publisher reaction;
        Publisher schedule;
        Publisher subscriber;
        Process delayed;

        @Override
        public Object invoke() {
            event(kill);
            return null;
        }
    }

    Object stale = new Object();
    Object error = new Object();

    ThreadLocal<Process> current = new ThreadLocal<>();
    ThreadLocal<Process> delayed = new ThreadLocal<>();

    static boolean lt(int[] x, int[] y) {
        if (x == null) return true;
        if (y == null) return false;
        int xl = x.length;
        int yl = y.length;
        int ml = Math.min(xl, yl);
        for(int i = 0; i < ml; i++) {
            int xi = x[i];
            int yi = y[i];
            if (xi != yi) return xi < yi;
        }
        return xl > yl;
    }

    static Publisher link(Publisher x, Publisher y) {
        if (lt(x.ranks, y.ranks)) {
            y.sibling = x.child;
            x.child = y;
            return x;
        } else {
            x.sibling = y.child;
            y.child = x;
            return y;
        }
    }

    static Publisher dequeue(Publisher pub) {
        Publisher heap = null;
        Publisher prev = null;
        Publisher head = pub.child;
        pub.child = null;
        while (head != null) {
            Publisher next = head.sibling;
            head.sibling = null;
            if (prev == null) prev = head;
            else {
                head = link(prev, head);
                heap = heap == null ? head : link(heap, head);
                prev = null;
            }
            head = next;
        }
        return prev == null ? heap : heap == null ? prev : link(heap, prev);
    }

    static void schedule(Publisher pub) {
        Process ps = pub.process;
        Publisher sch = ps.schedule;
        if (sch == null) {
            ps.schedule = pub;
            ps.delayed = delayed.get();
            delayed.set(ps);
        } else ps.schedule = link(pub, sch);
    }

    static void pull(Publisher pub) {
        Process ps = pub.process;
        Publisher cur = ps.subscriber;
        ps.subscriber = pub;
        pub.value = error; // detect reentrant push, TODO improve error reporting
        try {
            pub.value = ((IDeref) pub.iterator).deref();
        } catch (Throwable e) {
            if (ps.error == ps) {
                ps.error = e;
                Publisher k = ps.kill;
                if (k.busy = !k.busy)
                    schedule(k);
            }
        }
        ps.subscriber = cur;
    }

    static void sample(Publisher pub) {
        for(;;) {
            pull(pub);
            if (pub.busy = !pub.busy) {
                if (pub.done) {
                    schedule(pub);
                    break;
                }
            } else break;
        }
    }

    static void touch(Publisher pub) {
        Process ps = pub.process;
        if (pub.done) {
            Publisher prv = pub.prev;
            pub.prev = null;
            if (pub == prv) ps.alive = null; else {
                Publisher nxt = pub.next;
                nxt.prev = prv;
                prv.next = nxt;
                if (ps.alive == pub) ps.alive = prv;
            }
        } else if (pub.active == pub) {
            pub.active = ps.active;
            ps.active = pub;
            pub.pending = 1;
            pull(pub);
        } else {
            if (pub.live) pub.value = stale;
            else sample(pub);
        }
    }

    static void ack(Publisher pub) {
        if (--pub.pending == 0) {
            pub.value = null;
            if (pub.busy = !pub.busy) schedule(pub);
        }
    }

    static void propagate(Publisher pub) {
        Process ps = pub.process;
        current.set(ps);
        do {
            ps.reaction = dequeue(pub);
            ps.current = pub;
            touch(pub);
            Subscription t = pub.subs;
            if (t != null) {
                pub.subs = null;
                Subscription s = t;
                do {
                    s = s.next;
                    s.prev = null;
                } while (s != t);
                Subscription n = t.next;
                do {
                    s = n;
                    n = s.next;
                    s.next = null;
                    if (0 < pub.pending) pub.pending++;
                    ps.subscriber = s.subscriber;
                    (pub.prev == null ? s.terminator : s.notifier).invoke();
                    ps.subscriber = null;
                } while (s != t);
            }
        } while ((pub = ps.reaction) != null);
        ps.current = null;
        current.set(null);
        while ((pub = ps.active) != null) {
            ps.active = pub.active;
            pub.active = pub.value == error ? null : pub;
            ack(pub);
        }
        if (ps.alive == null) if ((pub = ps.boot) != null) {
            ps.boot = null;
            Object e = ps.error;
            if (e == ps) ps.success.invoke(pub.value);
            else ps.failure.invoke(e);
        }
    }

    static void hook(Subscription s) {
        Publisher pub = s.subscribed;
        if (pub.prev == null) s.terminator.invoke(); else {
            Subscription p = pub.subs;
            pub.subs = s;
            if (p == null) s.prev = s.next = s; else {
                Subscription n = p.next;
                n.prev = p.next = s;
                s.prev = p;
                s.next = n;
            }
        }
    }

    static void cancel(Publisher pub) {
        if (pub.live) {
            pub.live = false;
            Process ps = pub.process;
            Publisher cur = ps.subscriber;
            ps.subscriber = pub;
            ((IFn) pub.iterator).invoke();
            ps.subscriber = cur;
            if (pub.value == stale) sample(pub);
        }
    }

    static Object failer(IFn n, IFn t, Throwable e) {
        n.invoke();
        Failer f = new Failer();
        f.terminator = t;
        f.error = e;
        return f;
    }

    static void free(Publisher pub) {
        if (pub.process != current.get())
            throw new Error("Cancellation failure : not in reactor context.");
        cancel(pub);
    }

    static Object subscribe(Publisher pub, IFn n, IFn t) {
        Process ps = pub.process;
        Publisher sub = ps.subscriber;
        if (ps != current.get())
            return failer(n, t, new Error("Subscription failure : not in reactor context."));
        if (sub == ps.boot)
            return failer(n, t, new Error("Subscription failure : not a subscriber."));
        Subscription s = new Subscription();
        s.notifier = n;
        s.terminator = t;
        s.subscriber = sub;
        s.subscribed = pub;
        if (pub == pub.active) hook(s); else {
            if (0 < pub.pending) pub.pending++;
            n.invoke();
        }
        return s;
    }

    static void unsubscribe(Subscription s) {
        Publisher sub = s.subscriber;
        Process ps = sub.process;
        if (ps != current.get())
            throw new Error("Unsubscription failure : not in reactor context.");
        Publisher pub = s.subscribed;
        if (pub != null) {
            s.subscribed = null;
            Subscription p = s.prev;
            if (p == null) {
                if (0 < pub.pending) ack(pub);
            } else {
                Subscription n = s.next;
                s.prev = s.next = null;
                if (p == s) pub.subs = null; else {
                    p.next = n;
                    n.prev = p;
                    if (pub.subs == s) pub.subs = p;
                }
                Publisher cur = ps.subscriber;
                ps.subscriber = sub;
                s.notifier.invoke();
                ps.subscriber = cur;
            }
        }
    }

    static Object push(Subscription s) {
        Publisher sub = s.subscriber;
        Process ps = sub.process;
        if (ps != current.get())
            throw new Error("Transfer failure : not in reactor context.");
        Object value;
        Publisher pub = s.subscribed;
        if (pub != null) {
            value = pub.value;
            if (0 < pub.pending) ack(pub);
            else if (value == stale) {
                sample(pub);
                value = pub.value;
            }
        } else value = error;
        Publisher cur = ps.subscriber;
        ps.subscriber = sub;
        if (value == error) {
            s.terminator.invoke();
            ps.subscriber = cur;
            return clojure.lang.Util.sneakyThrow(new Cancelled("Subscription cancelled."));
        } else {
            hook(s);
            ps.subscriber = cur;
            return value;
        }
    }

    static void event(Publisher pub) {
        Process ps = current.get();
        if (ps == null) {
            synchronized (pub.process) {
                if (pub.busy = !pub.busy)
                    propagate(pub);
            }
            while ((ps = delayed.get()) != null) {
                delayed.set(ps.delayed);
                ps.delayed = null;
                pub = ps.schedule;
                ps.schedule = null;
                synchronized (ps) {
                    propagate(pub);
                }
            }
        } else if (pub.busy = !pub.busy) if (ps == pub.process) if (lt(ps.current.ranks, pub.ranks)) {
            Publisher r = ps.reaction;
            ps.reaction = r == null ? pub : link(pub, r);
        } else schedule(pub); else schedule(pub);
    }

    Object kill = (IDeref) () -> {
        Process ps = current.get();
        Publisher t = ps.alive;
        if (t != null) {
            Publisher pub = t.next;
            for(;;) {
                cancel(pub);
                t = ps.alive;
                if (t == null) break;
                do pub = pub.next; while (pub.prev == null);
                if (pub == t.next) break;
            }
        }
        return true;
    };

    int[] zero = new int[0];

    static Object run(IFn init, IFn success, IFn failure) {
        Process ps = new Process();
        Publisher k = new Publisher();
        k.iterator = kill;
        k.process = ps;
        k.ranks = null;
        k.value = false;
        Publisher b = new Publisher();
        b.iterator = (IDeref) init::invoke;
        b.process = ps;
        b.ranks = zero;
        ps.kill = k;
        ps.boot = b;
        ps.error = ps;
        ps.success = success;
        ps.failure = failure;
        event(b);
        return ps;
    }

    static Object publish(IFn flow, boolean continuous) {
        Process ps = current.get();
        if (ps == null) throw new Error("Publication failure : not in reactor context.");
        Publisher cur = ps.subscriber;
        Publisher pub = new Publisher();
        int size = cur.ranks.length;
        int[] ranks = new int[size + 1];
        System.arraycopy(cur.ranks, 0, ranks, 0, size);
        ranks[size] = cur.children++;
        pub.process = ps;
        pub.ranks = ranks;
        pub.busy = pub.live = true;
        if (!continuous) pub.active = pub;
        Publisher p = ps.alive;
        if (p == null) pub.prev = pub.next = pub; else {
            Publisher n = p.next;
            p.next = n.prev = pub;
            pub.prev = p;
            pub.next = n;
        }
        ps.alive = pub;
        ps.subscriber = pub;
        pub.iterator = flow.invoke(new AFn() {
            @Override
            public Object invoke() {
                event(pub);
                return null;
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                pub.done = true;
                event(pub);
                return null;
            }
        });
        ps.subscriber = cur;
        if ((Boolean) ps.kill.value) cancel(pub);
        if (pub.busy = !pub.busy) touch(pub);
        else if (continuous) {
            cancel(pub);
            throw new Error("Publication failure : undefined continuous flow.");
        }
        return pub;
    }

}