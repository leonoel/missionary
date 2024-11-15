package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IFn;
import missionary.Cancelled;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public interface Sleep {
    Scheduler S = new Scheduler();
    Cancelled C = new Cancelled("Sleep cancelled.");

    final class Scheduler extends Thread {
        final Lock L = new ReentrantLock();
        final Condition C = L.newCondition();

        Process queue = null;

        Scheduler() {
            super("missionary scheduler");
            setDaemon(true);
            start();
        }

        @Override
        public void run() {
            for(;;) try {
                IFn s = null;
                Object x = null;
                L.lock();
                Process head = queue;
                if (head == null) C.await(); else {
                    long d = head.time - System.currentTimeMillis();
                    if (0 < d) C.await(d, TimeUnit.MILLISECONDS); else {
                        s = head.success;
                        x = head.payload;
                        head.payload = null;
                        head.success = null;
                        head.failure = null;
                        queue = dequeue(head);
                    }
                }
                L.unlock();
                if (s != null) s.invoke(x);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    final class Process extends AFn {
        static {
            Util.printDefault(Process.class);
        }

        Object payload;
        IFn success;
        IFn failure;
        long time;
        Process sibling;
        Process child;

        @Override
        public Object invoke() {
            IFn f;
            S.L.lock();
            f = failure;
            failure = null;
            success = null;
            payload = null;
            S.L.unlock();
            return f == null ? null : f.invoke(C);
        }
    }

    static Process link(Process x, Process y) {
        if (x.time < y.time) {
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

    static Process run(long d, Object x, IFn s, IFn f) {
        Process ps = new Process();
        ps.payload = x;
        ps.success = s;
        ps.failure = f;
        ps.time = System.currentTimeMillis() + d;
        S.L.lock();
        Process head = S.queue;
        S.queue = head == null ? ps : link(head, ps);
        S.C.signal();
        S.L.unlock();
        return ps;
    }
}