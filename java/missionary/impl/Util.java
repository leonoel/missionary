package missionary.impl;

import clojure.lang.*;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

final class Util {
    static IFn NOP = new AFn() {
        @Override
        public Object invoke() {
            return null;
        }
    };

    static<T> void swap(T that, AtomicReferenceFieldUpdater<T, IFn> field, IFn cancel) {
        for(;;) {
            IFn current = field.get(that);
            if (current == null) {
                cancel.invoke();
                break;
            }
            if (field.compareAndSet(that, current, cancel)) break;
        }
    }

    static Object apply(IFn f, Object[] args) {
        switch (args.length) {
            case 0: return f.invoke();
            case 1: return f.invoke(args[0]);
            case 2: return f.invoke(args[0], args[1]);
            case 3: return f.invoke(args[0], args[1], args[2]);
            case 4: return f.invoke(args[0], args[1], args[2], args[3]);
            case 5: return f.invoke(args[0], args[1], args[2], args[3], args[4]);
            case 6: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5]);
            case 7: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
            case 8: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
            case 9: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8]);
            case 10: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9]);
            case 11: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10]);
            case 12: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11]);
            case 13: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12]);
            case 14: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13]);
            case 15: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14]);
            case 16: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15]);
            case 17: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16]);
            case 18: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17]);
            case 19: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17], args[18]);
            case 20: return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17], args[18], args[19]);
            default:
                Object[] rest = new Object[args.length - 20];
                System.arraycopy(args, 20, rest, 0, rest.length);
                return f.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17], args[18], args[19], rest);
        }
    }

    static void printDefault(Class c) {
        MultiFn pm = (MultiFn) ((Var) clojure.java.api.Clojure.var("clojure.core", "print-method")).deref();
        pm.addMethod(c, pm.getMethod(Object.class));
    }

    static void discard(Object it) {
        try {
            ((IDeref) it).deref();
        } catch (Throwable e) {
        }
    }

}
