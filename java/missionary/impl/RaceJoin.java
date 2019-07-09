package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.RT;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public final class RaceJoin extends AFn {

    static final AtomicIntegerFieldUpdater<RaceJoin> RACE =
            AtomicIntegerFieldUpdater.newUpdater(RaceJoin.class, "race");
    static final AtomicIntegerFieldUpdater<RaceJoin> JOIN =
            AtomicIntegerFieldUpdater.newUpdater(RaceJoin.class, "join");

    IFn raceCallback;
    IFn joinCallback;
    IFn combinator;

    Object[] cancel;
    Object[] result;

    volatile int race = -2;
    volatile int join = 0;

    void terminated() {
        if (result.length == JOIN.incrementAndGet(this)) {
            if (race < 0) try {
                joinCallback.invoke(Util.apply(combinator, result));
            } catch (Throwable e) {
                raceCallback.invoke(e);
            } else raceCallback.invoke(result[race]);
        }
    }

    public RaceJoin(boolean r, IFn c, Object tasks, IFn s, IFn f) {
        raceCallback = r ? s : f;
        joinCallback = r ? f : s;
        combinator = c;
        Iterator it = RT.iter(tasks);
        int count = RT.count(tasks);
        cancel = new Object[count];
        result = new Object[count];
        int i = 0;
        do {
            int index = i++;
            IFn joinCallback = new AFn() {
                @Override
                public Object invoke(Object x) {
                    result[index] = x;
                    terminated();
                    return null;
                }
            };
            IFn raceCallback = new AFn() {
                @Override
                public Object invoke(Object x) {
                    for(;;) {
                        int r = race;
                        if (0 <= r) break;
                        if (RACE.compareAndSet(RaceJoin.this, r, index)) {
                            if (r == -1) RaceJoin.this.invoke();
                            break;
                        }
                    }
                    return joinCallback.invoke(x);
                }
            };
            cancel[index] = ((IFn) it.next()).invoke(
                    r ? raceCallback : joinCallback,
                    r ? joinCallback : raceCallback);
        } while (it.hasNext());
        if (!RACE.compareAndSet(this, -2, -1)) invoke();
    }

    @Override
    public Object invoke() {
        for(Object c : cancel) {
            ((IFn) c).invoke();
        }
        return null;
    }
}
