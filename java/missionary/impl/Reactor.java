package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.IDeref;

public interface Reactor {

    ThreadLocal<Context> CURRENT = new ThreadLocal<>();
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

    static Publisher insert(Publisher root, Publisher pub) {
        return root == null ? pub : link(pub, root);
    }

    static Publisher remove(Publisher root) {
        Publisher heap = null;
        Publisher prev = null;
        Publisher head = root.child;
        root.child = root;
        while(head != null) {
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
        Context ctx = pub.context;
        Publisher emi = ctx.emitter;
        if (emi != null && lt(emi.ranks, pub.ranks))
            ctx.today = insert(ctx.today, pub);
        else ctx.tomorrow = insert(ctx.tomorrow, pub);
    }

    static void attach(Subscription sub) {
        Publisher pub = sub.subscribed;
        Subscription prv = pub.tail;
        pub.tail = sub;
        sub.prev = prv;
        if (prv == null) pub.head = sub; else prv.next = sub;
    }

    static void detach(Publisher pub) {
        Context ctx = pub.context;
        Publisher prv = pub.prev;
        Publisher nxt = pub.next;
        if (prv == null) ctx.head = nxt; else prv.next = nxt;
        if (nxt == null) ctx.tail = prv; else nxt.prev = prv;
        pub.prev = pub;
        pub.next = null;
    }

    static void signal(Publisher pub, IFn f) {
        Context ctx = pub.context;
        Publisher cur = ctx.current;
        ctx.current = pub;
        f.invoke();
        ctx.current = cur;
    }

    static void cancel(Context ctx) {
        ctx.cancelled = null;
        for(Publisher pub = ctx.head; pub != null; pub = ctx.head) pub.invoke();
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
        Context ctx = pub.context;
        Publisher cur = ctx.current;
        ctx.current = pub;
        Object val = CURRENT;
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

    static Context enter(Context ctx) {
        Context cur = CURRENT.get();
        if (ctx != cur) CURRENT.set(ctx);
        return cur;
    }

    static void emit(Publisher pub) {
        Subscription head = pub.head;
        pub.head = null;
        pub.tail = null;
        int p = 1;
        for(Subscription sub = head; sub != null; sub = sub.next) {
            sub.prev = sub;
            p++;
        }
        if (pub.pending == 0) {
            pub.pending = p;
            if (CURRENT == transfer(pub)) {
                pub.child = pub;
                pub.pending = -1;
                pub.active = null;
            } else {
                pub.active = pub.context.active;
                pub.context.active = pub;
            }
        } else {
            pub.value = CURRENT;
            pub.active = null;
        }
        for (Subscription sub = head; sub != null; sub = head) {
            head = head.next;
            sub.next = null;
            signal(sub.subscriber, sub.notifier);
        }
    }

    static Publisher finish(Context ctx) {
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

    static void leave(Context ctx, Context prv) {
        if (ctx != prv) {
            while ((ctx.emitter = finish(ctx)) != null) {
                do {
                    ctx.today = remove(ctx.emitter);
                    emit(ctx.emitter);
                } while ((ctx.emitter = ctx.today) != null);
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

        @Override
        public Object invoke() {
            Context ctx = subscribed.context;
            synchronized (ctx) {
                if (prev == this) notifier = null;
                else {
                    Context cur = enter(ctx);
                    cancel(this);
                    leave(ctx, cur);
                }
                return null;
            }
        }

        @Override
        public Object deref() {
            Publisher pub = subscribed;
            Context ctx = pub.context;
            synchronized(ctx) {
                Context cur = enter(ctx);
                Object val = pub.value;
                if (0 < pub.pending) ack(pub);
                if (val == CURRENT && pub.prev != pub) val = transfer(pub);
                if (notifier == null || (pub.prev == pub && pub.child == pub))
                    signal(subscriber, terminator); else attach(this);
                leave(ctx, cur);
                return val == CURRENT ? clojure.lang.Util.sneakyThrow(ERR_SUB_CANCEL) : val;
            }
        }
    }

    final class Publisher extends AFn {

        Context context;
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
            Context ctx = context;
            synchronized (ctx) {
                if (prev != this) {
                    Context cur = enter(ctx);
                    if (value != CURRENT || (transfer(this) != CURRENT && prev != this)) cancel(this);
                    leave(ctx, cur);
                }
                return null;
            }
        }

        @Override
        public Object invoke(Object n, Object t) {
            IFn notifier = (IFn) n;
            IFn terminator = (IFn) t;
            Context ctx = context;
            Publisher cur = ctx.current;
            if (ctx != CURRENT.get() || cur == null)
                return new Failer(notifier, terminator, ERR_SUB_ORPHAN);
            if (this == cur || lt(cur.ranks, ranks))
                return new Failer(notifier, terminator, ERR_SUB_CYCLIC);
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

    final class Context extends AFn {

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
                Context cur = enter(this);
                cancel(this);
                leave(this, cur);
            }
            return null;
        }
    }

    static Object context(IFn b, IFn s, IFn f) {
        Context ctx = new Context();
        ctx.cancelled = f;
        synchronized (ctx) {
            Context cur = enter(ctx);
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
        Context ctx = CURRENT.get();
        if (ctx == null) clojure.lang.Util.sneakyThrow(ERR_PUB_ORPHAN);
        if (ctx.cancelled == null) clojure.lang.Util.sneakyThrow(ERR_PUB_CANCEL);
        Publisher pub = new Publisher();
        pub.context = ctx;
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
                Context ctx = pub.context;
                synchronized (ctx) {
                    Context cur = enter(ctx);
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
                Context ctx = pub.context;
                synchronized (ctx) {
                    Context cur = enter(ctx);
                    ctx.running--;
                    if (pub.prev != pub) {
                        detach(pub);
                        for(Subscription sub = pub.head; sub != null; sub = pub.head) cancel(sub);
                    }
                    leave(ctx, cur);
                    return null;
                }
            }
        });
        pub.pending = d ? 0 : -1;
        if (pub.child == null) {
            pub.child = pub;
            emit(pub);
        }
        ctx.current = par;
        return pub;
    }

}