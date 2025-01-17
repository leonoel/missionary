package missionary.impl;

import clojure.lang.IFn;
import clojure.lang.RT;

public interface PairingHeap {
    class Impl {
        final IFn lt;
        final IFn getChild;
        final IFn setChild;
        final IFn getSibling;
        final IFn setSibling;

        public Impl(IFn lt, IFn getChild, IFn setChild, IFn getSibling, IFn setSibling) {
            this.lt = lt;
            this.getChild = getChild;
            this.setChild = setChild;
            this.getSibling = getSibling;
            this.setSibling = setSibling;
        }
    }

    static Impl impl(IFn lt, IFn getChild, IFn setChild, IFn getSibling, IFn setSibling) {
        return new Impl(lt, getChild, setChild, getSibling, setSibling);
    }

    static Object meld(Impl impl, Object inst, Object x, Object y) {
        if (RT.booleanCast(impl.lt.invoke(inst, x, y))) {
            impl.setSibling.invoke(inst, y, impl.getChild.invoke(inst, x));
            impl.setChild.invoke(inst, x, y);
            return x;
        } else {
            impl.setSibling.invoke(inst, x, impl.getChild.invoke(inst, y));
            impl.setChild.invoke(inst, y, x);
            return y;
        }
    }

    static Object dmin(Impl impl, Object inst, Object x) {
        Object head = impl.getChild.invoke(inst, x);
        if (head == null) return null; else {
            impl.setChild.invoke(inst, x, null);
            Object heap = null;
            Object prev = null;
            for(;;) {
                Object next = impl.getSibling.invoke(inst, head);
                if (prev == null) if (next == null) if (head == null) return heap;
                else return heap == null ? head : meld(impl, inst, heap, head);
                else {
                    prev = head;
                    head = next;
                } else {
                    head = meld(impl, inst, prev, head);
                    heap = heap == null ? head : meld(impl, inst, heap, head);
                    if (next == null) return heap; else {
                        prev = null;
                        head = next;
                    }
                }
            }
        }
    }

}