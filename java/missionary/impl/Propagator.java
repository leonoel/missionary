package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import missionary.Cancelled;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public interface Propagator {

    class Publisher extends AFn implements Comparable<Publisher> {
        static {
            Util.printDefault(Publisher.class);
        }

        final int[] ranks;
        final Object initp;
        final Object inits;
        final IFn perform;
        final IFn subscribe;
        final IFn lcb;
        final IFn rcb;
        final IFn tick;
        final IFn accept;
        final IFn reject;

        final ReentrantLock lock = new ReentrantLock();
        final AtomicInteger children = new AtomicInteger();

        IFn effect;
        Process current;

        Publisher(int[] ranks, Object initp, Object inits, IFn perform, IFn subscribe,
                  IFn lcb, IFn rcb, IFn tick, IFn accept, IFn reject, IFn effect) {
            this.ranks = ranks;
            this.initp = initp;
            this.inits = inits;
            this.perform = perform;
            this.subscribe = subscribe;
            this.lcb = lcb;
            this.rcb = rcb;
            this.tick = tick;
            this.accept = accept;
            this.reject = reject;
            this.effect = effect;
        }

        @Override
        public Object invoke(Object l, Object r) {
            return sub(this, (IFn) l, (IFn) r);
        }

        @Override
        public int compareTo(Publisher that) {
            return this == that ? 0 : lt(this.ranks, that.ranks) ? -1 : 1;
        }
    }

    class Process {
        final Publisher parent;

        Object state;
        Object process;
        Subscription waiting;
        Subscription pending;
        Process child;
        Process sibling;
        Subscription prop;

        Process(Publisher parent) {
            this.parent = parent;
        }
    }

    class Subscription extends AFn implements IDeref {
        static {
            Util.printDefault(Subscription.class);
        }

        final Process source;
        final Process target;
        final IFn lcb;
        final IFn rcb;

        Subscription prev;
        Subscription next;
        Subscription prop;

        Object state;
        boolean flag;          // if task : success. if flow : pending

        Subscription(Process source, Process target, IFn lcb, IFn rcb) {
            this.source = source;
            this.target = target;
            this.lcb = lcb;
            this.rcb = rcb;
        }

        @Override
        public Object invoke() {
            return unsub(this);
        }

        @Override
        public Object deref() {
            return accept(this);
        }
    }

    class Context {
        long time;               // time increment
        Process process;         // process currently running
        Subscription sub;        // subscription currently running
        int[] cursor;            // progress of current propagation turn
        Process reacted;         // pairing heap of processes scheduled for this turn
        Process delayed;         // pairing heap of processes scheduled for next turn
    }

    ThreadLocal<Context> context = ThreadLocal.withInitial(Context::new);

    AtomicInteger children = new AtomicInteger();

    int[] ceiling = new int[0];

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

    static Process link(Process x, Process y) {
        if (lt(x.parent.ranks, y.parent.ranks)) {
            y.sibling = x.child;
            x.child = y;
            return x;
        } else {
            x.sibling = y.child;
            y.child = x;
            return y;
        }
    }

    static Process dequeue(Process ps) {
        Process heap = null;
        Process prev = null;
        Process head = ps.child;
        ps.child = ps;
        while (head != null) {
            Process next = head.sibling;
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

    static Process enqueue(Process r, Process p) {
        p.child = null;
        return r == null ? p : link(p, r);
    }

    static boolean enter(Publisher pub) {
        boolean top = !pub.lock.isHeldByCurrentThread();
        if (top) pub.lock.lock();
        return top;
    }

    static void propagate(Context ctx) {
        Process ps = ctx.process;
        Publisher pub = ps.parent;
        Subscription sub = ps.prop;
        ps.prop = null;
        pub.lock.unlock();
        ctx.sub = null;
        if (pub.accept == null) while (sub != null) {
            Subscription n = sub.prop;
            sub.prop = null;
            ctx.process = sub.source;
            (sub.flag ? sub.lcb : sub.rcb).invoke(sub.state);
            sub = n;
        } else while (sub != null) {
            Subscription n = sub.prop;
            sub.prop = null;
            ctx.process = sub.source;
            (sub.flag ? sub.lcb : sub.rcb).invoke();
            sub = n;
        }
    }

    static void exit(Context ctx, boolean top, boolean idle, Process p, Subscription s) {
        if (top) propagate(ctx);
        if (idle) {
            Process ps;
            while ((ps = ctx.delayed) != null) {
                ctx.delayed = null;
                do {
                    ctx.reacted = dequeue(ps);
                    Publisher pub = ps.parent;
                    if (ps == pub.current) {
                        ctx.process = ps;
                        ctx.cursor = pub.ranks;
                        pub.lock.lock();
                        pub.tick.invoke();
                        propagate(ctx);
                    }
                } while ((ps = ctx.reacted) != null);
                ctx.time++;
            }
            ctx.process = null;
            ctx.cursor = null;
        } else {
            ctx.sub = s;
            ctx.process = p;
        }
    }

    static void attach(Subscription n, Subscription s) {
        if (n == null) {
            s.prev = s;
            s.next = s;
        } else {
            Subscription p = n.prev;
            s.next = n;
            s.prev = p;
            p.next = s;
            n.prev = s;
        }
    }

    static void dispatch(Subscription s) {
        Process ps = s.target;
        Subscription p = s.prev;
        Subscription n = s.next;
        s.prev = s.next = null;
        if (p == s) ps.waiting = null; else {
            n.prev = p;
            p.next = n;
            ps.waiting = n;
        }
        s.prop = ps.prop;
        ps.prop = s;
    }

    static void detach(Subscription s) {
        Process ps = s.target;
        Subscription p = s.prev;
        Subscription n = s.next;
        s.prev = s.next = null;
        if (p == s) ps.pending = null; else {
            n.prev = p;
            p.next = n;
            ps.pending = n;
        }
    }

    static void foreach(Context ctx, Subscription subs, IFn f) {
        if (subs != null) {
            Subscription s = ctx.sub;
            Subscription sub = subs.next;
            for(;;) {
                Subscription n = sub.next;
                ctx.sub = sub;
                f.invoke();
                if (sub == subs) break;
                else sub = n;
            }
            ctx.sub = s;
        }
    }

    static boolean touch(Context ctx) {
        if (ctx.cursor == null) {
            ctx.cursor = ceiling;
            return true;
        } else return false;
    }

    static Object accept(Subscription sub) {
        Context ctx = context.get();
        Process ps = sub.target;
        Publisher pub = ps.parent;
        boolean top = enter(pub);
        boolean idle = touch(ctx);
        Process p = ctx.process;
        Subscription s = ctx.sub;
        try {
            ctx.process = ps;
            ctx.sub = sub;
            sub.flag = false;
            if (sub.next == null) {
                sub.prop = ps.prop;
                ps.prop = sub;
                return clojure.lang.Util.sneakyThrow(new Cancelled("Flow publisher cancelled."));
            } else {
                detach(sub);
                attach(ps.waiting, ps.waiting = sub);
                return pub.accept.invoke();
            }
        } finally {
            exit(ctx, top, idle, p, s);
        }
    }

    static void cancel(Process ps) {
        Publisher pub = ps.parent;
        pub.current = null;
        ((IFn) ps.process).invoke();
        if (ps.child != ps) pub.tick.invoke();
    }

    static Object unsub(Subscription sub) {
        Context ctx = context.get();
        Process ps = sub.target;
        Publisher pub = ps.parent;
        boolean top = enter(pub);
        boolean idle = touch(ctx);
        Process p = ctx.process;
        Subscription s = ctx.sub;
        try {
            ctx.process = ps;
            ctx.sub = sub;
            if (sub.next != null) if (pub.effect != null) if (pub.current == ps) {
                if (pub.accept == null) if (sub.next == sub) cancel(ps); else {
                    sub.state = new Cancelled("Task publisher cancelled.");
                    dispatch(sub);
                } else if (sub.flag) if (sub.next == sub && ps.waiting == null) cancel(ps); else {
                    detach(sub);
                    pub.reject.invoke();
                } else if (sub.next == sub && ps.pending == null) cancel(ps); else {
                    sub.flag = true;
                    dispatch(sub);
                }
            }
            return null;
        } finally {
            exit(ctx, top, idle, p, s);
        }
    }

    static IFn bind(Process ps, IFn f) {
        return new AFn() {
            @Override
            public Object invoke() {
                Context ctx = context.get();
                boolean top = enter(ps.parent);
                boolean idle = touch(ctx);
                Process p = ctx.process;
                Subscription s = ctx.sub;
                try {
                    ctx.process = ps;
                    ctx.sub = null;
                    return f.invoke();
                } finally {
                    exit(ctx, top, idle, p, s);
                }
            }

            @Override
            public Object invoke(Object x) {
                Context ctx = context.get();
                boolean top = enter(ps.parent);
                boolean idle = touch(ctx);
                Process p = ctx.process;
                Subscription s = ctx.sub;
                try {
                    ctx.process = ps;
                    ctx.sub = null;
                    return f.invoke(x);
                } finally {
                    exit(ctx, top, idle, p, s);
                }
            }
        };
    }

    static Subscription sub(Publisher pub, IFn lcb, IFn rcb) {
        Context ctx = context.get();
        boolean top = enter(pub);
        boolean idle = touch(ctx);
        Process p = ctx.process;
        Subscription s = ctx.sub;
        try {
            Process ps = pub.current;
            if (ps == null) {
                ps = new Process(pub);
                ps.state = pub.initp;
                ps.child = ps;
                pub.current = ps;
                ctx.process = ps;
                ctx.sub = null;
                pub.perform.invoke();
                ps.process = pub.effect.invoke(bind(ps, pub.lcb), bind(ps, pub.rcb));
            } else ctx.process = ps;
            Subscription sub = new Subscription(p, ps, lcb, rcb);
            sub.state = pub.inits;
            attach(ps.waiting, ps.waiting = sub);
            ctx.sub = sub;
            pub.subscribe.invoke();
            return sub;
        } finally {
            exit(ctx, top, idle, p, s);
        }
    }

    static int[] ranks() {
        Process ps = context.get().process;
        if (ps == null) return new int[] {children.getAndIncrement()}; else {
            Publisher p = ps.parent;
            int[] r = p.ranks;
            int s = r.length;
            int[] ranks = new int[s + 1];
            System.arraycopy(r, 0, ranks, 0, s);
            ranks[s] = p.children.getAndIncrement();
            return ranks;
        }
    }

    // public API

    static long time() {
        return context.get().time;
    }

    static Object transfer() {
        return ((IDeref) context.get().process.process).deref();
    }

    static Object getp() {
        return context.get().process.state;
    }

    static void setp(Object x) {
        context.get().process.state = x;
    }

    static Object gets() {
        return context.get().sub.state;
    }

    static void sets(Object x) {
        context.get().sub.state = x;
    }

    static void success(Object x) {
        Subscription sub = context.get().sub;
        sub.flag = true;
        sub.state = x;
        dispatch(sub);
    }

    static void failure(Object x) {
        Subscription sub = context.get().sub;
        sub.state = x;
        dispatch(sub);
    }

    static void step() {
        Subscription sub = context.get().sub;
        sub.flag = true;
        dispatch(sub);
        Process ps = sub.target;
        attach(ps.pending, ps.pending = sub);
    }

    static void done() {
        Subscription sub = context.get().sub;
        dispatch(sub);
    }

    static void waiting(IFn f) {
        Context ctx = context.get();
        foreach(ctx, ctx.process.waiting, f);
    }

    static void pending(IFn f) {
        Context ctx = context.get();
        foreach(ctx, ctx.process.pending, f);
    }

    static void schedule() {
        Context ctx = context.get();
        Process ps = ctx.process;
        Publisher pub = ps.parent;
        if (ps.process == null || pub.current != ps)
            pub.tick.invoke();
        else if (lt(ctx.cursor, pub.ranks))
            ctx.reacted = enqueue(ctx.reacted, ps);
        else
            ctx.delayed = enqueue(ctx.delayed, ps);
    }

    static void resolve() {
        Process ps = context.get().process;
        Publisher pub = ps.parent;
        if (ps == pub.current) pub.effect = null;
    }

    static Publisher task(Object initp, Object inits,
                          IFn perform, IFn subscribe, IFn success, IFn failure,
                          IFn tick, IFn task) {
        return new Publisher(ranks(), initp, inits, perform, subscribe, success, failure, tick, null, null, task);
    }

    static Publisher flow(Object initp, Object inits,
                          IFn perform, IFn subscribe, IFn step, IFn done,
                          IFn tick, IFn accept, IFn reject, IFn flow) {
        return new Publisher(ranks(), initp, inits, perform, subscribe, step, done, tick, accept, reject, flow);
    }
}
