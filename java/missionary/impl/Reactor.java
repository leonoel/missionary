package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.IDeref;

public interface Reactor {

    ThreadLocal<Process> CURRENT = new ThreadLocal<>();
    Throwable ERR_PUB_ORPHAN = new Exception("Publication failure : not in reactor context.");
    Throwable ERR_PUB_CANCEL = new Exception("Publication failure : reactor cancelled.");
    Throwable ERR_SUB_ORPHAN = new Exception("Subscription failure : not in publisher context.");
    Throwable ERR_SUB_CANCEL = new Exception("Subscription failure : publisher cancelled.");
    Throwable ERR_SUB_CYCLIC = new Exception("Subscription failure : cyclic dependency.");

    static boolean lt(int[] x, int[] y) {
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

    static Publisher enqueue(Publisher root, Publisher pub) {
        return root == null ? pub : link(pub, root);
    }

    static Publisher dequeue(Publisher root) {
        Publisher heap = null;
        Publisher prev = null;
        Publisher head = root.child;
        root.child = root;
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
        Process ctx = pub.process;
        Publisher emi = ctx.emitter;
        if (emi != null && lt(emi.ranks, pub.ranks))
            ctx.today = enqueue(ctx.today, pub);
        else ctx.tomorrow = enqueue(ctx.tomorrow, pub);
    }

    static void attach(Subscription sub) {
        Publisher pub = sub.subscribed;
        Subscription prv = pub.tail;
        pub.tail = sub;
        sub.prev = prv;
        if (prv == null) pub.head = sub; else prv.next = sub;
    }

    static void detach(Publisher pub) {
        Process ctx = pub.process;
        Publisher prv = pub.prev;
        Publisher nxt = pub.next;
        if (prv == null) ctx.head = nxt; else prv.next = nxt;
        if (nxt == null) ctx.tail = prv; else nxt.prev = prv;
        pub.prev = pub;
        pub.next = null;
    }

    static void signal(Publisher pub, IFn f) {
        Process ctx = pub.process;
        Publisher cur = ctx.current;
        ctx.current = pub;
        f.invoke();
        ctx.current = cur;
    }

    static void cancel(Process ctx) {
        ctx.cancelled = null;
        for (Publisher pub = ctx.head; pub != null; pub = ctx.head) pub.invoke();
    }

    static void cancel(Publisher pub) {
        detach(pub);
        signal(pub, (IFn) pub.iterator);
        if (pub.child == pub) {
            pub.child = null;
            if (pub.pending < 1) schedule(pub);
        } else try {
            ((IDeref) pub.iterator).deref();
        } catch (Throwable e) {}
        if (pub.pending == 0) pub.pending = -1;
    }

    static void cancel(Subscription sub) {
        Publisher pub = sub.subscribed;
        Subscription prv = sub.prev;
        Subscription nxt = sub.next;
        if (prv == null) pub.head = nxt; else prv.next = nxt;
        if (nxt == null) pub.tail = prv; else nxt.prev = prv;
        sub.prev = sub;
        sub.next = null;
        signal(sub.subscriber, sub.terminator);
    }

    static Object transfer(Publisher pub) {
        Process ctx = pub.process;
        Publisher cur = ctx.current;
        ctx.current = pub;
        Object val = pub.ranks;
        try {
            val = ((IDeref) pub.iterator).deref();
        } catch (Throwable e) {
            if (pub.prev != pub) cancel(pub);
            if (ctx.cancelled != null) {
                ctx.result = e;
                ctx.completed = ctx.cancelled;
                cancel(ctx);
            }
        }
        ctx.current = cur;
        return pub.value = val;
    }

    static void ack(Publisher pub) {
        if (0 == --pub.pending) {
            pub.value = null;
            if (pub.prev == pub) pub.pending = -1;
            if (pub.child == null) schedule(pub);
        }
    }

    static Process enter(Process ctx) {
        Process cur = CURRENT.get();
        if (ctx != cur) CURRENT.set(ctx);
        return cur;
    }

    static void emit(Publisher pub) {
        Object stale = pub.ranks;
        Process ctx = pub.process;
        Publisher prv = ctx.emitter;
        Subscription head = pub.head;
        int p = 1;
        for (Subscription sub = head; sub != null; sub = sub.next) {
            sub.prev = sub;
            p++;
        }
        pub.head = null;
        pub.tail = null;
        ctx.emitter = pub;
        if (pub.pending == 0) {
            pub.pending = p;
            if (stale == transfer(pub)) {
                pub.child = pub;
                pub.pending = -1;
                pub.active = null;
            } else {
                pub.active = pub.process.active;
                pub.process.active = pub;
            }
        } else {
            pub.value = stale;
            pub.active = null;
        }
        for (Subscription sub = head; sub != null; sub = head) {
            head = head.next;
            sub.next = null;
            signal(sub.subscriber, sub.notifier);
        }
        ctx.emitter = prv;
    }

    static Publisher done(Process ctx) {
        Publisher pub;
        while ((pub = ctx.active) != null) {
            ctx.active = pub.active;
            pub.active = pub;
            ack(pub);
        }
        pub = ctx.tomorrow;
        ctx.tomorrow = null;
        return pub;
    }

    static void leave(Process ctx, Process prv) {
        if (ctx != prv) {
            Publisher pub;
            while ((pub = done(ctx)) != null) {
                do {
                    ctx.today = dequeue(pub);
                    emit(pub);
                } while ((pub = ctx.today) != null);
            }
            if (ctx.running == 0) {
                ctx.cancelled = null;
                ctx.completed.invoke(ctx.result);
            }
            CURRENT.set(prv);
        }
    }

    final class Subscription extends AFn implements IDeref {

        IFn notifier;
        IFn terminator;
        Publisher subscriber;
        Publisher subscribed;
        Subscription prev;
        Subscription next;
        boolean cancelled;

        @Override
        public Object invoke() {
            Process ctx = subscribed.process;
            synchronized (ctx) {
                if (prev == this) cancelled = true;
                else {
                    Process cur = enter(ctx);
                    cancel(this);
                    leave(ctx, cur);
                }
                return null;
            }
        }

        @Override
        public Object deref() {
            Publisher pub = subscribed;
            Process ctx = pub.process;
            Object stale = pub.ranks;
            synchronized (ctx) {
                Process cur = enter(ctx);
                Object val = pub.value;
                if (0 < pub.pending) ack(pub);
                if (val == stale && pub.prev != pub) {
                    pub.pending = 1;
                    for(;;) {
                        val = transfer(pub);
                        if (pub.child == pub) break;
                        else pub.child = pub;
                    }
                    pub.pending = -1;
                }
                if (cancelled || (pub.prev == pub && pub.child == pub))
                    signal(subscriber, terminator); else attach(this);
                leave(ctx, cur);
                return val == stale ? clojure.lang.Util.sneakyThrow(ERR_SUB_CANCEL) : val;
            }
        }
    }

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

    static Object failer(IFn n, IFn t, Throwable e) {
        n.invoke();
        Failer f = new Failer();
        f.terminator = t;
        f.error = e;
        return f;
    }

    final class Publisher extends AFn {

        static {
            Util.printDefault(Publisher.class);
        }

        Process process;
        int[] ranks;
        Object iterator;
        Object value;
        int children;
        int pending;
        Publisher prev;
        Publisher next;
        Publisher child;
        Publisher sibling;
        Publisher active;
        Subscription head;
        Subscription tail;

        @Override
        public Object invoke() {
            Process ctx = process;
            synchronized (ctx) {
                if (prev != this) {
                    Process cur = enter(ctx);
                    if (value != ranks || (transfer(this) != ranks && prev != this)) cancel(this);
                    leave(ctx, cur);
                }
                return null;
            }
        }

        @Override
        public Object invoke(Object n, Object t) {
            IFn notifier = (IFn) n;
            IFn terminator = (IFn) t;
            Process ctx = process;
            Publisher cur = ctx.current;
            if (ctx != CURRENT.get() || cur == null)
                return failer(notifier, terminator, ERR_SUB_ORPHAN);
            if (this == cur || lt(cur.ranks, ranks))
                return failer(notifier, terminator, ERR_SUB_CYCLIC);
            Subscription sub = new Subscription();
            sub.notifier = notifier;
            sub.terminator = terminator;
            sub.subscriber = cur;
            sub.subscribed = this;
            sub.prev = sub;
            if (this == active) {
                if (this == prev) terminator.invoke();
                else attach(sub);
            } else {
                if (0 < pending) pending++;
                notifier.invoke();
            }
            return sub;
        }
    }

    final class Process extends AFn {

        static {
            Util.printDefault(Process.class);
        }

        IFn completed;
        IFn cancelled;
        Object result;
        int children;
        int running;
        Publisher active;
        Publisher current;
        Publisher emitter;
        Publisher today;
        Publisher tomorrow;
        Publisher head;
        Publisher tail;

        @Override
        public synchronized Object invoke() {
            if (cancelled != null) {
                Process cur = enter(this);
                cancel(this);
                leave(this, cur);
            }
            return null;
        }
    }

    static Object run(IFn b, IFn s, IFn f) {
        Process ctx = new Process();
        ctx.cancelled = f;
        synchronized (ctx) {
            Process cur = enter(ctx);
            try {
                Object r = b.invoke();
                if (ctx.cancelled != null) {
                    ctx.result = r;
                    ctx.completed = s;
                }
            } catch (Throwable e) {
                if (ctx.cancelled != null) {
                    ctx.result = e;
                    ctx.completed = ctx.cancelled;
                    cancel(ctx);
                }
            }
            leave(ctx, cur);
            return ctx;
        }
    }

    static Object publish(IFn f, boolean d) {
        Process ctx = CURRENT.get();
        if (ctx == null) clojure.lang.Util.sneakyThrow(ERR_PUB_ORPHAN);
        if (ctx.cancelled == null) clojure.lang.Util.sneakyThrow(ERR_PUB_CANCEL);
        Publisher pub = new Publisher();
        pub.process = ctx;
        pub.active = pub;
        pub.child = pub;
        pub.pending = 1;
        Publisher prv = ctx.tail;
        ctx.tail = pub;
        pub.prev = prv;
        if (prv == null) ctx.head = pub; else prv.next = pub;
        Publisher par = ctx.current;
        if (par == null) pub.ranks = new int[] {ctx.children++};
        else {
            int size = par.ranks.length;
            pub.ranks = new int[size + 1];
            System.arraycopy(par.ranks, 0, pub.ranks, 0, size);
            pub.ranks[size] = par.children++;
        }
        ctx.running++;
        ctx.current = pub;
        pub.iterator = f.invoke(new AFn() {
            @Override
            public Object invoke() {
                Process ctx = pub.process;
                synchronized (ctx) {
                    Process cur = enter(ctx);
                    if (pub.prev != pub) {
                        pub.child = null;
                        if (pub.pending < 1) schedule(pub);
                    } else try {
                        ((IDeref) pub.iterator).deref();
                    } catch (Throwable e) {}
                    leave(ctx, cur);
                    return null;
                }
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                Process ctx = pub.process;
                synchronized (ctx) {
                    Process cur = enter(ctx);
                    ctx.running--;
                    if (pub.prev != pub) {
                        detach(pub);
                        for (Subscription sub = pub.head; sub != null; sub = pub.head) cancel(sub);
                    }
                    leave(ctx, cur);
                    return null;
                }
            }
        });
        pub.pending = d ? 0 : -1;
        ctx.current = par;
        if (pub.child == null) {
            pub.child = pub;
            emit(pub);
        } else if (!d) {
            cancel(pub);
            throw new Error("Undefined continuous flow.");
        }
        return pub;
    }

}