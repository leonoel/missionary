package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public interface Propagator {

    class Publisher extends AFn implements Comparable<Publisher> {
        static {
            Util.printDefault(Publisher.class);
        }

        final int[] ranks;
        final ReentrantLock lock;
        final AtomicInteger children;

        Process current;
        Subscription prop;
        Publisher child;
        Publisher sibling;

        Publisher(int[] ranks) {
            this.ranks = ranks;
            this.lock = new ReentrantLock();
            this.children = new AtomicInteger();
        }

        @Override
        public Object invoke(Object lcb, Object rcb) {
            Context ctx = context.get();
            boolean held = enter(this);
            boolean busy = ctx.busy;
            Process node = ctx.node;
            Subscription edge = ctx.edge;
            Process ps = this.current;
            Subscription s = new Subscription((IFn) lcb, (IFn) rcb, ps, node);
            ctx.busy = true;
            ctx.node = ps;
            ctx.edge = s;
            s.state = ((IDeref) ps.state).deref();
            exit(this, ctx, held, busy, node, edge);
            return s;
        }

        @Override
        public int compareTo(Publisher that) {
            return this == that ? 0 : lt(this.ranks, that.ranks) ? -1 : 1;
        }
    }

    class Process {
        final Publisher parent;
        final Object state;
        Subscription subs;

        Process(Publisher parent, Object state) {
            this.parent = parent;
            this.state = state;
        }
    }

    class Subscription extends AFn implements IDeref {
        static {
            Util.printDefault(Subscription.class);
        }

        final IFn lcb;
        final IFn rcb;
        final Process pub;
        final Process sub;

        boolean flag;
        Object value;
        Object state;
        Subscription prev;
        Subscription next;

        Subscription(IFn lcb, IFn rcb, Process pub, Process sub) {
            this.lcb = lcb;
            this.rcb = rcb;
            this.pub = pub;
            this.sub = sub;
        }

        @Override
        public Object invoke() {
            Context ctx = context.get();
            Process ps = this.pub;
            Publisher pub = ps.parent;
            boolean held = enter(pub);
            boolean busy = ctx.busy;
            Process node = ctx.node;
            Subscription edge = ctx.edge;
            ctx.busy = true;
            ctx.node = ps;
            ctx.edge = this;
            ((IFn) this.state).invoke();
            exit(pub, ctx, held, busy, node, edge);
            return null;
        }

        @Override
        public Object deref() {
            Context ctx = context.get();
            Process ps = this.pub;
            Publisher pub = ps.parent;
            boolean held = enter(pub);
            boolean busy = ctx.busy;
            Process node = ctx.node;
            Subscription edge = ctx.edge;
            ctx.busy = true;
            ctx.node = ps;
            ctx.edge = this;
            try {
                return ((IDeref) this.state).deref();
            } finally {
                exit(pub, ctx, held, busy, node, edge);
            }
        }
    }

    class Context {
        long step;               // time increment
        boolean busy;            // currently propagating
        Process node;            // node currently touched
        Subscription edge;       // edge currently touched
        Publisher emitter;       // publisher currently emitting
        Publisher reacted;       // pairing heap of publishers scheduled for this turn.
        Publisher delayed;       // pairing heap of publishers scheduled for next turn.
    }

    ThreadLocal<Context> context = ThreadLocal.withInitial(Context::new);

    AtomicInteger children = new AtomicInteger();

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

    static Publisher enqueue(Publisher r, Publisher p) {
        return r == null ? p : link(p, r);
    }

    static boolean enter(Publisher pub) {
        boolean held = pub.lock.isHeldByCurrentThread();
        pub.lock.lock();
        return held;
    }

    static void callback(Context ctx, Subscription s) {
        ctx.edge = null;
        while (s != null) {
            IFn cb = s.flag ? s.rcb : s.lcb;
            Object value = s.value;
            Subscription n = s.next;
            s.value = null;
            s.next = null;
            ctx.node = s.sub;
            if (value == none) cb.invoke();
            else cb.invoke(value);
            s = n;
        }
    }

    static void tick(Publisher pub, Context ctx) {
        pub.lock.lock();
        Process ps = pub.current;
        ctx.reacted = dequeue(pub);
        ctx.emitter = pub;
        ctx.node = ps;
        ((IFn) ps.state).invoke();
        Subscription s = pub.prop;
        pub.prop = null;
        pub.lock.unlock();
        callback(ctx, s);
    }

    static void exit(Publisher pub, Context ctx, boolean held, boolean busy, Process node, Subscription edge) {
        Subscription s;
        if (held) s = null; else {
            s = pub.prop;
            pub.prop = null;
        }
        pub.lock.unlock();
        callback(ctx, s);
        if (!busy) {
            ctx.edge = null;
            for(;;) {
                pub = ctx.reacted;
                if (pub == null) {
                    ctx.step++;
                    pub = ctx.delayed;
                    if (pub == null) break; else {
                        ctx.delayed = null;
                        tick(pub, ctx);
                    }
                } else tick(pub, ctx);
            }
            ctx.emitter = null;
        }
        ctx.busy = busy;
        ctx.node = node;
        ctx.edge = edge;
    }


    // public API

    Object none = new Object();

    static long step() {
        return context.get().step;
    }

    static void reset(Object state) {
        Publisher pub = context.get().node.parent;
        pub.current = new Process(pub, state);
    }

    static boolean attached() {
        return context.get().edge.prev != null;
    }

    static void detach(boolean flag, Object value) {
        Context ctx = context.get();
        Process ps = ctx.node;
        Subscription s = ctx.edge;
        Subscription p = s.prev;
        Subscription n = s.next;
        Publisher pub = ps.parent;
        s.value = value;
        s.flag = flag;
        s.prev = null;
        s.next = pub.prop;
        pub.prop = s;
        if (p == s) ps.subs = null; else {
            n.prev = p;
            p.next = n;
            ps.subs = n;
        }
    }

    static void dispatch(boolean flag, Object value) {
        Context ctx = context.get();
        Process ps = ctx.node;
        Publisher pub = ps.parent;
        Subscription subs = ps.subs;
        if (subs != null) {
            ps.subs = null;
            Subscription s = subs;
            for(;;) {
                s.value = value;
                s.flag = flag;
                s.prev = null;
                Subscription n = s.next;
                if (n == subs) break;
                else s = n;
            }
            s.next = pub.prop;
            pub.prop = subs;
        }
    }

    static void schedule() {
        Context ctx = context.get();
        Process ps = ctx.node;
        Publisher pub = ps.parent;
        if (pub.current == ps) {
            Publisher emitter = ctx.emitter;
            if (emitter == null || lt(emitter.ranks, pub.ranks))
                ctx.reacted = enqueue(ctx.reacted, pub);
            else ctx.delayed = enqueue(ctx.delayed, pub);
        } else ((IFn) ps.state).invoke();
    }

    static void attach() {
        Context ctx = context.get();
        Process ps = ctx.node;
        Subscription s = ctx.edge;
        Subscription n = ps.subs;
        if (n == null) {
            ps.subs = s;
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

    static IFn bind(IFn f) {
        Process ps = context.get().node;
        return new AFn() {
            @Override
            public Object invoke() {
                Context ctx = context.get();
                Publisher pub = ps.parent;
                boolean held = enter(pub);
                boolean busy = ctx.busy;
                Process node = ctx.node;
                Subscription edge = ctx.edge;
                ctx.busy = true;
                ctx.node = ps;
                ctx.edge = null;
                f.invoke();
                exit(pub, ctx, held, busy, node, edge);
                return null;
            }

            @Override
            public Object invoke(Object x) {
                Context ctx = context.get();
                Publisher pub = ps.parent;
                boolean held = enter(pub);
                boolean busy = ctx.busy;
                Process node = ctx.node;
                Subscription edge = ctx.edge;
                ctx.busy = true;
                ctx.node = ps;
                ctx.edge = null;
                f.invoke(x);
                exit(pub, ctx, held, busy, node, edge);
                return null;
            }
        };
    }

    static Publisher publisher(Object state) {
        int[] ranks;
        Process ps = context.get().node;
        if (ps == null) ranks = new int[] {children.getAndIncrement()}; else {
            Publisher p = ps.parent;
            int[] r = p.ranks;
            int s = r.length;
            ranks = new int[s + 1];
            ranks[s] = p.children.getAndIncrement();
            System.arraycopy(r, 0, ranks, 0, s);
        }
        Publisher pub = new Publisher(ranks);
        pub.current = new Process(pub, state);
        return pub;
    }
}
