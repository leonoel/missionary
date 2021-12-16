package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

public interface GroupBy {

    class Process extends AFn implements IDeref {

        static {
            Util.printDefault(Process.class);
        }

        IFn keyfn;
        IFn notifier;
        IFn terminator;
        Object key;
        Object value;
        Object input;
        Group[] table;
        int load;
        boolean busy;
        boolean done;

        @Override
        public Object invoke() {
            return ((IFn) input).invoke();
        }

        @Override
        public Object deref() {
            return sample(this);
        }
    }

    class Group extends AFn implements IDeref {

        static {
            Util.printDefault(Group.class);
        }

        Process process;
        Object key;
        IFn notifier;
        IFn terminator;

        @Override
        public Object invoke() {
            return cancel(this);
        }

        @Override
        public Object deref() {
            return consume(this);
        }
    }

    static Object sample(Process p) {
        Object k = p.key;
        return k == p ? clojure.lang.Util.sneakyThrow((Throwable) p.value) :
                new MapEntry(k, new AFn() {
                    @Override
                    public Object invoke(Object n, Object t) {
                        return group(p, k, (IFn) n, (IFn) t);
                    }
                });
    }

    static int step(int i, int m) {
        return i == m ? 0 : i + 1;
    }

    static void insert(Process p, int i, Group g) {
        Group[] table = p.table;
        table[i] = g;
        int s = table.length << 1;
        if (s <= ++p.load * 3) {
            Group[] larger = p.table = new Group[s];
            int m = s - 1;
            for (Group h : table) if (h != null) {
                i = clojure.lang.Util.hasheq(h.key) & m;
                while (larger[i] != null) i = step(i, m);
                larger[i] = h;
            }
        }
    }

    static Group group(Process p, Object k, IFn n, IFn t) {
        Group g = new Group();
        g.key = k;
        g.notifier = n;
        g.terminator = t;
        synchronized (p) {
            Group[] table = p.table;
            if (table == null) t.invoke();
            else {
                int m = table.length - 1;
                int i = clojure.lang.Util.hasheq(k) & m;
                Group h;
                while ((h = table[i]) != null && !clojure.lang.Util.equiv(h.key, k)) i = step(i, m);
                if (h == null && clojure.lang.Util.equiv(p.key, k)) insert(g.process = p, i, g);
                n.invoke();
            }
        }
        return g;
    }

    static void delete(Process p, int i, int m) {
        p.load--;
        Group[] table = p.table;
        Group h;
        int j;
        while ((table[j = i] = null) != (h = table[i = step(i, m)]) &&
                (clojure.lang.Util.hasheq(h.key) & m) != i) table[j] = h;
    }

    static Object cancel(Group g) {
        Process p = g.process;
        if (p != null) synchronized (p) {
            g.process = null;
            Object k = g.key;
            Group[] table = p.table;
            int m = table.length - 1;
            int i = clojure.lang.Util.hasheq(k) & m;
            while (table[i] != g) i = step(i, m);
            delete(p, i, m);
            (clojure.lang.Util.equiv(p.key, k) ? p.notifier : g.notifier).invoke();
        }
        return null;
    }

    static Object consume(Group g) {
        Process p = g.process;
        if (p == null) {
            g.terminator.invoke();
            return clojure.lang.Util.sneakyThrow(new Cancelled("Group consumer cancelled."));
        } else synchronized (p) {
            Object v = p.value;
            p.value = p;
            p.key = p;
            transfer(p);
            return v;
        }
    }

    static void transfer(Process p) {
        while (p.busy = !p.busy) if (p.done) {
            for (Group g : p.table) if (g != null) {
                g.process = null;
                g.terminator.invoke();
            }
            p.table = null;
            p.terminator.invoke();
            break;
        } else if (p.value == p) try {
            Object k = p.key = p.keyfn.invoke(p.value = ((IDeref) p.input).deref());
            Group[] table = p.table;
            int m = table.length - 1;
            int i = clojure.lang.Util.hasheq(k) & m;
            Group h;
            while ((h = table[i]) != null && !clojure.lang.Util.equiv(h.key, k)) i = step(i, m);
            (h == null ? p.notifier : h.notifier).invoke();
            break;
        } catch (Throwable e) {
            ((IFn) p.input).invoke();
            p.value = e;
            p.notifier.invoke();
        } else try {
            ((IDeref) p.input).deref();
        } catch (Throwable e) {}
    }

    static Process run(IFn k, IFn f, IFn n, IFn t) {
        Process p = new Process();
        p.keyfn = k;
        p.notifier = n;
        p.terminator = t;
        p.key = p;
        p.value = p;
        p.busy = true;
        p.table = new Group[8];
        p.input = f.invoke(new AFn() {
            @Override
            public Object invoke() {
                synchronized (p) {
                    transfer(p);
                    return null;
                }
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                synchronized (p) {
                    p.done = true;
                    transfer(p);
                    return null;
                }
            }
        });
        synchronized (p) {
            transfer(p);
        }
        return p;
    }
}
