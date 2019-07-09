package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.PersistentVector;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.ReentrantLock;

public interface Reactor {

    ThreadLocal<Dag> CURRENT = new ThreadLocal<>();

    int PENDING = 0;
    int READY = 1;
    int DONE = 2;

    final class Dag extends AFn {

        ReentrantLock lock = new ReentrantLock();

        IFn success;
        IFn failure;

        Object result;
        boolean failed;
        boolean cancelled;
        int tier;

        Pub current;
        Pub emitter;

        // pairing heaps of scheduled nodes, cf https://www.cs.cmu.edu/~sleator/papers/pairing-heaps.pdf
        Pub today;
        Pub tomorrow;

        // linked list of graph nodes
        Pub head;
        Pub tail;

        @Override
        public Object invoke() {
            Dag p = acquire(this);
            if (!cancelled) {
                cancelled = true;
                for(Pub n = head; n != null; n = n.next) n.invoke();
            }
            release(this, p);
            return null;
        }

    }

    final class Pub extends AFn {

        Dag dag;
        boolean discrete;
        int[] id;
        Object iterator;
        Object value = CURRENT;
        boolean cancelled;
        boolean scheduled;
        int tier;
        int state;
        int pressure;

        // linked list of sibling graph nodes
        Pub prev;
        Pub next;

        // linked list of subscriptions
        Sub head;
        Sub tail;

        // pairing heap of scheduled nodes
        Pub child;
        Pub sibling;

        @Override
        public Object invoke() {
            Dag prv = acquire(dag);
            if (!cancelled) {
                cancelled = true;
                schedule(this);
                ((IFn) iterator).invoke();
            }
            release(dag, prv);
            return null;
        }

        @Override
        public Object invoke(Object n, Object t) {
            Dag prv = acquire(dag);
            assert (dag.current != null);
            assert (lt(id, dag.current.id));
            Sub sub = new Sub();
            sub.up = this;
            sub.down = dag.current;
            sub.notifier = (IFn) n;
            sub.terminator = (IFn) t;
            if (state == DONE) sub.terminator.invoke();
            else {
                sub.prev = tail;
                tail = sub;
                if (sub.prev == null) head = sub; else sub.prev.next = sub;
                if (value != CURRENT && !(scheduled && lt(dag.emitter.id, id))) push(sub);
            }
            release(dag, prv);
            return sub;
        }

    }

    final class Sub extends AFn implements IDeref {

        Pub up;
        Pub down;

        IFn notifier;
        IFn terminator;

        int state;

        // linked list of sibling subscriptions
        Sub prev;
        Sub next;

        @Override
        public Object invoke() {
            Dag prv = acquire(up.dag);
            if (state != DONE) {
                if (state == READY) pull(this); else terminator.invoke();
                state = DONE;
                if (prev == null) up.head = next; else prev.next = next;
                if (next == null) up.tail = prev; else next.prev = prev;
            }
            release(up.dag, prv);
            return null;
        }

        @Override
        public Object deref() {
            Dag prv = acquire(up.dag);
            if (up.state == READY) more(up);
            Object x = up.value;
            if (state == DONE) terminator.invoke(); else pull(this);
            release(up.dag, prv);
            if (x == CURRENT) throw new NoSuchElementException();
            return x;
        }

    }

    static boolean lt(int[] x, int[] y) {
        int xl = x.length;
        int yl = y.length;
        int ml = Math.min(xl, yl);
        for(int i = 0; i < ml; i++) {
            int xi = x[i];
            int yi = y[i];
            if (xi != yi) return xi < yi;
        }
        return xl < yl;
    }

    static Pub link(Pub x, Pub y) {
        if (lt(x.id, y.id)) {
            y.sibling = x.child;
            x.child = y;
            return x;
        } else {
            x.sibling = y.child;
            y.child = x;
            return y;
        }
    }

    static Dag acquire(Dag g) {
        g.lock.lock();
        Dag p = CURRENT.get();
        CURRENT.set(g);
        return p;
    }

    static void release(Dag g, Dag p) {
        if (g.lock.getHoldCount() == 1) {
            while ((g.emitter = g.tomorrow) != null) {
                g.tomorrow = null;
                do {
                    Pub emit = g.emitter;
                    Pub heap = null;
                    Pub prev = null;
                    Pub head = emit.child;
                    while(head != null) {
                        Pub next = head.sibling;
                        head.sibling = null;
                        if (prev == null) prev = head;
                        else {
                            head = link(prev, head);
                            heap = heap == null ? head : link(heap, head);
                            prev = null;
                        }
                        head = next;
                    }
                    g.today = prev == null ? heap : heap == null ? prev : link(heap, prev);
                    emit.child = null;
                    emit.scheduled = false;
                    if (emit.discrete) emit.value = CURRENT;
                    if (emit.state != PENDING && !(emit.discrete && 0 < emit.pressure)) {
                        if (emit.state == READY) {
                            if (emit.discrete || emit.cancelled) {
                                more(emit);
                                schedule(emit);
                            } else emit.value = null;
                            for(Sub e = emit.head; e != null; e = e.next) if (e.state == PENDING) {
                                Pub c = g.current;
                                g.current = e.down;
                                push(e);
                                g.current = c;
                            }
                        } else {
                            for(Sub e = emit.head; e != null; e = e.next) e.invoke();
                            if (emit.prev == null) g.head = emit.next; else emit.prev.next = emit.next;
                            if (emit.next == null) g.tail = emit.prev; else emit.next.prev = emit.prev;
                        }
                    }
                } while ((g.emitter = g.today) != null);
            }
            if (g.head == null) (g.failed ? g.failure : g.success).invoke(g.result);
        }
        CURRENT.set(p);
        g.lock.unlock();
    }

    static void schedule(Pub n) {
        if (!n.scheduled) {
            n.scheduled = true;
            if (n.dag.emitter != null && lt(n.dag.emitter.id, n.id))
                n.dag.today = n.dag.today == null ? n : link(n, n.dag.today);
            else n.dag.tomorrow = n.dag.tomorrow == null ? n : link(n, n.dag.tomorrow);
        }
    }

    static void more(Pub pub) {
        Pub prv = pub.dag.current;
        pub.dag.current = pub;
        pub.state = PENDING;
        try {
            pub.value = ((IDeref) pub.iterator).deref();
        } catch (Throwable e) {
            if (!pub.dag.failed) {
                pub.dag.failed = true;
                pub.dag.result = e;
                pub.dag.invoke();
            }
            pub.value = CURRENT;
        }
        pub.dag.current = prv;
    }

    static void push(Sub sub) {
        sub.state = READY;
        sub.up.pressure++;
        sub.notifier.invoke();
    }

    static void pull(Sub sub) {
        sub.state = PENDING;
        sub.up.pressure--;
        schedule(sub.up);
    }

    static Dag dag(IFn i, IFn s, IFn f) {
        Dag dag = new Dag();
        dag.success = s;
        dag.failure = f;
        Dag prv = acquire(dag);
        try {
            dag.result = i.invoke();
        } catch (Throwable e) {
            dag.invoke();
            dag.failed = true;
            dag.result = e;
        }
        release(dag, prv);
        return dag;
    }

    static Pub pub(IFn f, boolean d) {
        Dag dag = CURRENT.get();
        assert (dag != null) : "Unable to publish : not in reactor.";
        Pub pub = new Pub();
        pub.dag = dag;
        pub.discrete = d;
        if (dag.current == null) pub.id = new int[] {dag.tier++};
        else {
            int size = dag.current.id.length;
            pub.id = new int[size + 1];
            System.arraycopy(dag.current.id, 0, pub.id, 0, size);
            pub.id[size] = dag.current.tier++;
        }
        pub.prev = dag.tail;
        dag.tail = pub;
        if (pub.prev == null) dag.head = pub; else pub.prev.next = pub;
        Pub prv = dag.current;
        dag.current = pub;
        pub.iterator = f.invoke(new AFn() {
            @Override
            public Object invoke() {
                Dag prv = acquire(dag);
                pub.state = READY;
                schedule(pub);
                release(dag, prv);
                return null;
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                Dag prv = acquire(dag);
                pub.state = DONE;
                schedule(pub);
                release(dag, prv);
                return null;
            }
        });
        if (dag.cancelled) pub.invoke();
        dag.current = prv;
        return pub;
    }

}