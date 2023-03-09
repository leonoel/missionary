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
        boolean live;
        boolean busy;
        boolean done;

        @Override
        public Object invoke(Object n, Object t) {
            return group(this, (IFn) n, (IFn) t);
        }

        @Override
        public Object invoke() {
            kill(this);
            return null;
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
            cancel(this);
            return null;
        }

        @Override
        public Object deref() {
            return consume(this);
        }
    }

    static void kill(Process p) {
        if (p.live) {
            p.live = false;
            ((IFn) p.input).invoke();
        }
    }

    static int step(int i, int m) {
        return (i + 1) & m;
    }

    static Group group(Process p, IFn n, IFn t) {
        Group g = new Group();
        g.process = p;
        g.notifier = n;
        g.terminator = t;
        synchronized (p) {
            Group[] table = p.table;
            Object k = g.key = p.key;
            if (k != p) {
                p.key = p;
                int s = table.length;
                int m = s - 1;
                int i = clojure.lang.Util.hasheq(k) & m;
                while (table[i] != null) i = step(i, m);
                table[i] = g;
                int ss = s << 1;
                if (ss <= ++p.load * 3) {
                    int mm = ss - 1;
                    Group[] larger = p.table = new Group[ss];
                    Group h;
                    for (i = 0; i < s; i++) if ((h = table[i]) != null) {
                        int j = clojure.lang.Util.hasheq(h.key) & mm;
                        while (larger[j] != null) j = step(j, mm);
                        larger[j] = h;
                    }
                }
            }
        }
        n.invoke();
        return g;
    }

    static void cancel(Group g) {
        Process p = g.process;
        IFn cb = null;
        synchronized (p) {
            Object k = g.key;
            if (p.live) if (k != p) {
                g.key = p;
                Group[] table = p.table;
                int m = table.length - 1;
                int i = clojure.lang.Util.hasheq(k) & m;
                while (table[i] != g) i = step(i, m);
                table[i] = null;
                p.load--;
                Group h;
                while ((h = table[i = step(i, m)]) != null) {
                    int j = clojure.lang.Util.hasheq(h.key) & m;
                    if (i != j) {
                        table[i] = null;
                        while (table[j] != null) j = step(j, m);
                        table[j] = h;
                    }
                }
                cb = clojure.lang.Util.equiv(p.key, k) ? p.notifier : g.notifier;
            }
        }
        if (cb != null) cb.invoke();
    }

    static void transfer(Process p) {
        IFn cb;
        Group[] table;
        synchronized (p) {
            table = p.table;
            for(;;) if (p.busy = !p.busy) if (p.done) {
                p.live = false;
                p.table = null;
                cb = p.terminator;
                break;
            } else if (p.value == p) try {
                Object k = p.key = p.keyfn.invoke(p.value = ((IDeref) p.input).deref());
                int m = table.length - 1;
                int i = clojure.lang.Util.hasheq(k) & m;
                for(;;) {
                    Group h = table[i];
                    if (h == null) {
                        cb = p.notifier;
                        break;
                    } else if (clojure.lang.Util.equiv(h.key, k)) {
                        cb = h.notifier;
                        break;
                    } else i = step(i, m);
                }
                table = null;
                break;
            } catch (Throwable e) {
                p.value = e;
                p.table = null;
                kill(p);
                cb = p.notifier;
                break;
            } else Util.discard(p.input); else {
                cb = null;
                table = null;
                break;
            }
        }
        if (table != null) for (Group g : table) if (g != null) g.terminator.invoke();
        if (cb != null) cb.invoke();
    }

    static Object sample(Process p) {
        Object k = p.key;
        if (k == p) {
            transfer(p);
            return clojure.lang.Util.sneakyThrow((Throwable) p.value);
        } else return new MapEntry(k, p);
    }

    static Object consume(Group g) {
        Process p = g.process;
        if (p == g.key) {
            g.terminator.invoke();
            return clojure.lang.Util.sneakyThrow(new Cancelled("Group consumer cancelled."));
        } else {
            Object v = p.value;
            p.value = p;
            p.key = p;
            transfer(p);
            return v;
        }
    }

    static Process run(IFn k, IFn f, IFn n, IFn t) {
        Process p = new Process();
        p.keyfn = k;
        p.notifier = n;
        p.terminator = t;
        p.key = p.value = p;
        p.live = p.busy = true;
        p.table = new Group[8];
        p.input = f.invoke(new AFn() {
            @Override
            public Object invoke() {
                transfer(p);
                return null;
            }
        }, new AFn() {
            @Override
            public Object invoke() {
                p.done = true;
                transfer(p);
                return null;
            }
        });
        transfer(p);
        return p;
    }
}
