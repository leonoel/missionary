package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import missionary.Cancelled;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public interface Propagator {

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

    class Publisher extends AFn implements Comparable<Publisher> {
        final int[] ranks;
        final Strategy strategy;
        final Object arg;
        final IFn effect;

        volatile int node;
        volatile int lock;

        Process current;

        Publisher(int[] ranks, Strategy strategy, Object arg, IFn effect) {
            this.ranks = ranks;
            this.strategy = strategy;
            this.arg = arg;
            this.effect = effect;
        }

        @Override
        public Object invoke(Object lcb, Object rcb) {
            return sub(this, (IFn) lcb, (IFn) rcb);
        }

        @Override
        public int compareTo(Publisher that) {
            return this == that ? 0 : lt(this.ranks, that.ranks) ? -1 : 1;
        }
    }

    class Process {
        final Publisher parent;
        volatile int pressure;

        Thread owner;
        Object input;

        Object child;
        Object sibling;

        Subscription ready;
        Subscription pending;

        boolean failed;
        boolean dirty;
        boolean flag;
        Object state;

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

        boolean ready;
        Object state;

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
            return transfer(this);
        }
    }

    class Context {
        int[] cursor;
        Process process;
        Process reacted;
        Process delayed;
        Subscription[] buffer = new Subscription[1];
    }

    interface Strategy {
        void tick(Process ps);
        void publish(Process ps);
        void refresh(Process ps);
        void subscribe(Subscription sub, boolean idle);
        void unsubscribe(Subscription sub, boolean idle);
        Object accept(Subscription sub, boolean idle);
        Object reject(Subscription sub, boolean idle);
    }

    ThreadLocal<Context> context = ThreadLocal.withInitial(Context::new);

    int[] ceiling = new int[0];

    AtomicInteger root = new AtomicInteger();

    AtomicIntegerFieldUpdater<Publisher> node =
            AtomicIntegerFieldUpdater.newUpdater(Publisher.class, "node");

    AtomicIntegerFieldUpdater<Publisher> lock =
            AtomicIntegerFieldUpdater.newUpdater(Publisher.class, "lock");

    AtomicIntegerFieldUpdater<Process> pressure =
            AtomicIntegerFieldUpdater.newUpdater(Process.class, "pressure");

    static void acquire(Publisher pub) {
        for(;;) if (lock.get(pub) == 1) Thread.yield();
        else if (0 == lock.getAndSet(pub, 1)) break;
    }

    static void release(Publisher pub) {
        lock.set(pub, 0);
    }

    PairingHeap.Impl impl = PairingHeap.impl(
            new AFn() {
                @Override
                public Object invoke(Object inst, Object x, Object y) {
                    return lt(((Process) x).parent.ranks, ((Process) y).parent.ranks);
                }
            },
            new AFn() {
                @Override
                public Object invoke(Object inst, Object x) {
                    return ((Process) x).child;
                }
            }, new AFn() {
                @Override
                public Object invoke(Object inst, Object x, Object y) {
                    ((Process) x).child = y;
                    return null;
                }
            },
            new AFn() {
                @Override
                public Object invoke(Object inst, Object x) {
                    return ((Process) x).sibling;
                }
            }, new AFn() {
                @Override
                public Object invoke(Object inst, Object x, Object y) {
                    ((Process) x).sibling = y;
                    return null;
                }
            }
    );

    static Process enqueue(Process r, Process p) {
        p.child = null;
        return r == null ? p : (Process) PairingHeap.meld(impl, null, p, r);
    }

    static void schedule(Process ps) {
        Publisher pub = ps.parent;
        Context ctx = context.get();
        if (lt(ctx.cursor, pub.ranks))
            ctx.reacted = enqueue(ctx.reacted, ps);
        else
            ctx.delayed = enqueue(ctx.delayed, ps);
    }

    static void crash(Process ps, Throwable e) {
        Publisher pub = ps.parent;
        ps.state = e;
        ps.failed = true;
        if (pub.current == ps) ((IFn) ps.input).invoke();
    }

    static boolean ack(Process ps) {
        return 0 == pressure.decrementAndGet(ps);
    }

    static Subscription detach(Subscription s) {
        Subscription p = s.prev;
        Subscription n = s.next;
        s.prev = null;
        s.next = null;
        if (p == s) return null; else {
            p.next = n;
            n.prev = p;
            return p;
        }
    }

    static void attach(Subscription p, Subscription s) {
        if (p == null) {
            s.prev = s;
            s.next = s;
        } else {
            Subscription n = p.next;
            p.next = s;
            s.prev = p;
            s.next = n;
            n.prev = s;
        }
    }

    static void union(Subscription p, Subscription s) {
        if (p != null) {
            Subscription n = p.next;
            Subscription t = s.next;
            p.next = t;
            t.prev = p;
            s.next = n;
            n.prev = s;
        }
    }

    static void clear(Subscription head) {
        Subscription sub = head;
        do {
            Subscription n = sub.next;
            sub.next = sub.prev = null;
            sub = n;
        } while (sub != head);
    }

    static void bufferize(Context ctx, Subscription head) {
        Subscription[] buf = ctx.buffer;
        int i = 0;
        Subscription sub = head;
        do {
            sub.ready = false;
            buf[i++] = sub;
            sub = sub.next;
            int cap = buf.length;
            if (i == cap) {
                Subscription[] arr = new Subscription[cap << 1];
                System.arraycopy(buf, 0, arr, 0, cap);
                buf = arr;
            }
        } while (sub != head);
        ctx.buffer = buf;
    }

    static void terminate(Context ctx, Process ps) {
        Publisher pub = ps.parent;
        ps.input = null;
        Subscription ready = ps.ready;
        if (ready != null) {
            ps.ready = null;
            bufferize(ctx, ready);
            clear(ready);
        }
        release(pub);
    }

    static void invalidate(Context ctx, Process ps) {
        Publisher pub = ps.parent;
        ps.dirty = true;
        Subscription ready = ps.ready;
        if (ready != null) {
            ps.ready = null;
            bufferize(ctx, ready);
            union(ps.pending, ps.pending = ready);
        }
        release(pub);
    }

    static void stepAll(Process ps) {
        Context ctx = context.get();
        invalidate(ctx, ps);
        int i = 0;
        Subscription[] buf = ctx.buffer;
        Subscription sub = buf[i];
        while (sub != null) {
            buf[i++] = null;
            ctx.process = sub.source;
            sub.lcb.invoke();
            sub = buf[i];
        }
    }

    static void doneAll(Process ps) {
        Context ctx = context.get();
        terminate(ctx, ps);
        int i = 0;
        Subscription[] buf = ctx.buffer;
        Subscription sub = buf[i];
        while (sub != null) {
            buf[i++] = null;
            ctx.process = sub.source;
            sub.rcb.invoke();
            sub = buf[i];
        }
    }

    static void successAll(Process ps) {
        Context ctx = context.get();
        terminate(ctx, ps);
        int i = 0;
        Subscription[] buf = ctx.buffer;
        Subscription sub = buf[i];
        while (sub != null) {
            buf[i++] = null;
            ctx.process = sub.source;
            sub.lcb.invoke(ps.state);
            sub = buf[i];
        }
    }

    static void failureAll(Process ps) {
        Context ctx = context.get();
        terminate(ctx, ps);
        int i = 0;
        Subscription[] buf = ctx.buffer;
        Subscription sub = buf[i];
        while (sub != null) {
            buf[i++] = null;
            ctx.process = sub.source;
            sub.rcb.invoke(ps.state);
            sub = buf[i];
        }
    }

    static void streamAck(Subscription sub) {
        Process ps = sub.target;
        if (null == (ps.pending = detach(sub))) {
            if (ps.owner == null) {
                ps.state = ps;
                if (ack(ps)) schedule(ps);
            }
        }
    }

    static void streamEmit(Process ps) {
        if (ps.flag) doneAll(ps); else {
            ps.owner = Thread.currentThread();
            schedule(ps);
            stepAll(ps);
        }
    }

    static void signalEmit(Process ps) {
        if (ps.flag) doneAll(ps); else stepAll(ps);
    }

    static void failedEmit(Process ps) {
        Publisher pub = ps.parent;
        for(;;) if (ps.flag) {
            doneAll(ps);
            break;
        } else {
            Util.discard(ps.input);
            if (!ack(ps)) {
                release(pub);
                break;
            }
        }
    }

    static void leave(Context ctx, boolean idle) {
        if (idle) {
            Process ps;
            while ((ps = ctx.delayed) != null) {
                ctx.delayed = null;
                do {
                    Publisher pub = ps.parent;
                    ctx.cursor = pub.ranks;
                    ctx.reacted = (Process) PairingHeap.dmin(impl, null, ps);
                    acquire(pub);
                    if (ps.failed) if (ps.owner == null) failedEmit(ps); else {
                        ps.owner = null;
                        if (ack(ps)) failedEmit(ps);
                        else release(pub);
                    } else pub.strategy.tick(ps);
                } while ((ps = ctx.reacted) != null);
            }
            ctx.cursor = null;
            ctx.process = null;
        }
    }

    static boolean enter(Context ctx) {
        if (ctx.cursor == null) {
            ctx.cursor = ceiling;
            return true;
        } else return false;
    }

    static void request(Subscription sub, boolean idle) {
        Process ps = sub.target;
        Publisher pub = ps.parent;
        Context ctx = context.get();
        sub.ready = true;
        attach(ps.ready, ps.ready = sub);
        release(pub);
        leave(ctx, idle);
    }

    static void step(Subscription sub, boolean idle) {
        Process ps = sub.target;
        Publisher pub = ps.parent;
        Context ctx = context.get();
        attach(ps.pending, ps.pending = sub);
        release(pub);
        sub.lcb.invoke();
        leave(ctx, idle);
    }

    static void done(Subscription sub, boolean idle) {
        Process ps = sub.target;
        Publisher pub = ps.parent;
        Context ctx = context.get();
        release(pub);
        sub.rcb.invoke();
        leave(ctx, idle);
    }

    static void success(Subscription sub, boolean idle) {
        Process ps = sub.target;
        Publisher pub = ps.parent;
        Context ctx = context.get();
        release(pub);
        sub.lcb.invoke(ps.state);
        leave(ctx, idle);
    }

    static void failure(Subscription sub, boolean idle) {
        Process ps = sub.target;
        Publisher pub = ps.parent;
        Context ctx = context.get();
        release(pub);
        sub.rcb.invoke(ps.state);
        leave(ctx, idle);
    }

    static void ready(Process ps) {
        if (ps.owner == Thread.currentThread()) ps.owner = null;
        else if (0 == pressure.incrementAndGet(ps)) {
            Context ctx = context.get();
            boolean idle = enter(ctx);
            schedule(ps);
            leave(ctx, idle);
        }
    }

    static Subscription sub(Publisher pub, IFn lcb, IFn rcb) {
        Context ctx = context.get();
        boolean idle = enter(ctx);
        acquire(pub);
        Process source = ctx.process;
        Process target = pub.current;
        if (target == null) {
            Process ps = new Process(pub);
            ps.owner = Thread.currentThread();
            ps.child = ps;
            ps.state = ps;
            pub.current = ps;
            ctx.process = ps;
            ps.input = pub.effect.invoke(new AFn() {
                @Override
                public Object invoke() {
                    ready(ps);
                    return null;
                }
                @Override
                public Object invoke(Object x) {
                    ps.state = x;
                    ready(ps);
                    return null;
                }
            }, new AFn() {
                @Override
                public Object invoke() {
                    ps.flag = true;
                    ready(ps);
                    return null;
                }
                @Override
                public Object invoke(Object x) {
                    ps.flag = true;
                    ps.state = x;
                    ready(ps);
                    return null;
                }
            });
            ctx.process = source;
            pub.strategy.publish(ps);
            target = ps;
        }
        Subscription sub = new Subscription(source, target, lcb, rcb);
        if (target.failed) step(sub, idle); else pub.strategy.subscribe(sub, idle);
        return sub;
    }

    static Object unsub(Subscription sub) {
        Process ps = sub.target;
        Publisher pub = ps.parent;
        Context ctx = context.get();
        boolean idle = enter(ctx);
        acquire(pub);
        if (sub.next == null || ps.input == null || pub.current != ps) {
            release(pub);
            leave(ctx, idle);
        } else if (null == (sub.ready ? ps.pending : ps.ready) && sub.next == sub) {
            pub.current = null;
            ((IFn) ps.input).invoke();
            release(pub);
            leave(ctx, idle);
        } else pub.strategy.unsubscribe(sub, idle);
        return null;
    }

    static Object transfer(Subscription sub) {
        Process ps = sub.target;
        Publisher pub = ps.parent;
        Context ctx = context.get();
        boolean idle = enter(ctx);
        acquire(pub);
        if (sub.next == null) return pub.strategy.reject(sub, idle); else {
            if (ps.dirty) {
                ps.dirty = false;
                Process p = ctx.process;
                ctx.process = ps;
                pub.strategy.refresh(ps);
                ctx.process = p;
            }
            if (ps.failed) {
                ps.pending = detach(sub);
                if (ps.input == null) done(sub, idle); else request(sub, idle);
                return clojure.lang.Util.sneakyThrow((Throwable) ps.state);
            } else return pub.strategy.accept(sub, idle);
        }
    }

    static int[] ranks(Context ctx) {
        Process ps = ctx.process;
        if (ps == null) return new int[] {root.getAndIncrement()}; else {
            Publisher p = ps.parent;
            int[] r = p.ranks;
            int s = r.length;
            int[] ranks = new int[s + 1];
            System.arraycopy(r, 0, ranks, 0, s);
            ranks[s] = node.getAndIncrement(p);
            return ranks;
        }
    }

    static Publisher publisher(Strategy strategy, Object arg, IFn effect) {
        return new Publisher(ranks(context.get()), strategy, arg, effect);
    }

    Strategy memo = new Strategy() {
        @Override
        public void tick(Process ps) {
            if (ps.flag) failureAll(ps);
            else successAll(ps);
        }

        @Override
        public void publish(Process ps) {
            if (ps.owner == null) {
                ps.input = null;
            } else {
                ps.owner = null;
                if (ack(ps)) schedule(ps);
            }
        }

        @Override
        public void refresh(Process ps) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subscribe(Subscription sub, boolean idle) {
            Process ps = sub.target;
            if (ps.input == null) if (ps.flag) failure(sub, idle);
            else success(sub, idle);
            else request(sub, idle);
        }

        @Override
        public void unsubscribe(Subscription sub, boolean idle) {
            Process ps = sub.target;
            Publisher pub = ps.parent;
            ps.ready = detach(sub);
            release(pub);
            sub.rcb.invoke(new Cancelled("Memo subscription cancelled."));
            leave(context.get(), idle);
        }

        @Override
        public Object accept(Subscription sub, boolean idle) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object reject(Subscription sub, boolean idle) {
            throw new UnsupportedOperationException();
        }
    };

    Strategy stream = new Strategy() {
        @Override
        public void tick(Process ps) {
            Publisher pub = ps.parent;
            if (ps.owner == null) streamEmit(ps); else {
                ps.owner = null;
                if (null == ps.pending) {
                    ps.state = ps;
                    if (ack(ps)) streamEmit(ps);
                    else release(pub);
                } else release(pub);
            }
        }

        @Override
        public void publish(Process ps) {
            if (ps.owner == null) if (ps.flag) {
                ps.input = null;
            } else {
                ps.dirty = true;
                ps.owner = Thread.currentThread();
                schedule(ps);
            } else {
                ps.owner = null;
                if (ack(ps)) schedule(ps);
            }
        }

        @Override
        public void refresh(Process ps) {
            Thread o = ps.owner;
            try {
                ps.owner = null;
                ps.state = ((IDeref) ps.input).deref();
                ps.owner = o;
            } catch (Throwable e) {
                crash(ps, e);
                ps.owner = o;
                if (o == null && ack(ps)) schedule(ps);
            }
        }

        @Override
        public void subscribe(Subscription sub, boolean idle) {
            Process ps = sub.target;
            if (ps.input == null) done(sub, idle);
            else if (ps.owner == Thread.currentThread()) step(sub, idle);
            else request(sub, idle);
        }

        @Override
        public void unsubscribe(Subscription sub, boolean idle) {
            Process ps = sub.target;
            Publisher pub = ps.parent;
            if (sub.ready) {
                ps.ready = detach(sub);
                release(pub);
                sub.lcb.invoke();
            } else {
                streamAck(sub);
                release(pub);
            }
            leave(context.get(), idle);
        }

        @Override
        public Object accept(Subscription sub, boolean idle) {
            Process ps = sub.target;
            Object result = ps.state;
            streamAck(sub);
            if (ps.input == null) done(sub, idle); else request(sub, idle);
            return result;
        }

        @Override
        public Object reject(Subscription sub, boolean idle) {
            done(sub, idle);
            return clojure.lang.Util.sneakyThrow(new Cancelled("Stream subscription cancelled."));
        }
    };

    Strategy signal = new Strategy() {
        @Override
        public void tick(Process ps) {
            Publisher pub = ps.parent;
            if (ps.owner == null) signalEmit(ps); else {
                ps.owner = null;
                if (ack(ps)) signalEmit(ps);
                else release(pub);
            }
        }

        @Override
        public void publish(Process ps) {
            if (ps.owner == null) if (ps.flag) {
                crash(ps, new Error("Empty flow."));
                ps.input = null;
            } else {
                ps.dirty = true;
            } else {
                crash(ps, new Error("Uninitialized flow."));
                schedule(ps);
            }
        }

        @Override
        public void refresh(Process ps) {
            Publisher pub = ps.parent;
            ps.owner = Thread.currentThread();
            schedule(ps);
            try {
                IDeref input = (IDeref) ps.input;
                IFn sg = (IFn) pub.arg;
                Object r = input.deref();
                while (ps.owner == null && !ps.flag) {
                    ps.owner = Thread.currentThread();
                    r = sg.invoke(r, input.deref());
                }
                ps.state = ps.state == ps ? r : sg.invoke(ps.state, r);
                Subscription head = ps.pending;
                Subscription sub = head;
                do {
                    sub.state = sub.state == ps ? r : sg.invoke(sub.state, r);
                    sub = sub.next;
                } while (sub != head);
            } catch (Throwable e) {
                crash(ps, e);
            }
        }

        @Override
        public void subscribe(Subscription sub, boolean idle) {
            Process ps = sub.target;
            sub.state = ps.state;
            step(sub, idle);
        }

        @Override
        public void unsubscribe(Subscription sub, boolean idle) {
            Process ps = sub.target;
            Publisher pub = ps.parent;
            if (sub.ready) {
                ps.ready = detach(sub);
                release(pub);
                sub.lcb.invoke();
            } else {
                ps.pending = detach(sub);
                release(pub);
            }
            leave(context.get(), idle);
        }

        @Override
        public Object accept(Subscription sub, boolean idle) {
            Process ps = sub.target;
            Object result = sub.state;
            sub.state = ps;
            ps.pending = detach(sub);
            if (ps.input == null) done(sub, idle); else request(sub, idle);
            return result;
        }

        @Override
        public Object reject(Subscription sub, boolean idle) {
            done(sub, idle);
            return clojure.lang.Util.sneakyThrow(new Cancelled("Signal subscription cancelled."));
        }
    };
}