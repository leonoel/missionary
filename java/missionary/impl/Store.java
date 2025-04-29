package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import missionary.Cancelled;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public interface Store {
    AtomicReferenceFieldUpdater<Port, Object> PORT_STATE = AtomicReferenceFieldUpdater.newUpdater(Port.class, Object.class, "state");
    AtomicReferenceFieldUpdater<Reader, Object> READER_STATE = AtomicReferenceFieldUpdater.newUpdater(Reader.class, Object.class, "state");

    class Call extends AFn implements IDeref {
        final IFn done;
        final IFn thunk;

        Call(IFn done, IFn thunk) {
            this.done = done;
            this.thunk = thunk;
        }

        @Override
        public Object invoke() {
            return null;
        }

        @Override
        public Object deref() {
            this.done.invoke();
            return this.thunk.invoke();
        }
    }

    class Frozen extends AFn {
        final Object result;
        final boolean failed;

        Frozen(boolean failed, Object result) {
            this.failed = failed;
            this.result = result;
        }

        @Override
        public Object invoke() {
            return this.failed ? clojure.lang.Util.sneakyThrow((Throwable) this.result) : this.result;
        }
    }

    class Port extends AFn {
        final IFn sg;
        volatile Object state;

        Port(IFn sg, Object init) {
            this.sg = sg;
            this.state = init;
        }

        @Override
        public Object invoke() {
            freeze(this);
            return null;
        }
        @Override
        public Object invoke(Object x) {
            append(this, x);
            return null;
        }
        @Override
        public Object invoke(Object s, Object d) {
            return spawn(this, (IFn) s, (IFn) d);
        }
    }

    class Reader extends AFn implements IDeref {
        final Port port;
        final IFn step;
        final IFn done;

        volatile Object state;
        Object last = zero;

        Reader(Port port, IFn step, IFn done, Object init) {
            this.port = port;
            this.step = step;
            this.done = done;
            this.state = init;
        }

        @Override
        public Object invoke() {
            cancel(this);
            return null;
        }

        public Object deref() {
            return transfer(this);
        }
    }

    Object over = new Object();
    Object zero = new Object();

    IFn concurrent = new AFn() {
        @Override
        public Object invoke() {
            throw new Error("Concurrent store reader.");
        }
    };

    static void terminate(Reader reader, Object last) {
        PORT_STATE.set(reader.port, last);
        reader.last = zero;
    }

    static void freeze(Port port) {
        for(;;) {
            Object s = PORT_STATE.get(port);
            if (s instanceof Reader) {
                Reader reader = (Reader) s;
                Object t = READER_STATE.get(reader);
                if (t == zero) {
                    if (READER_STATE.compareAndSet(reader, t, over)) {
                        terminate(reader, new Frozen(false, reader.last));
                        reader.done.invoke();
                        break;
                    }
                } else {
                    if (t instanceof Frozen) break;
                    if (READER_STATE.compareAndSet(reader, t, new Frozen(false, t))) break;
                }
            } else {
                if (s instanceof Frozen) break;
                if (PORT_STATE.compareAndSet(port, s, new Frozen(false, s))) break;
            }
        }
    }

    static Object collapse(Port port, Object x, Object y) {
        try {
            return port.sg.invoke(x, y);
        } catch (Throwable e) {
            return new Frozen(true, e);
        }
    }

    static void append(Port port, Object x) {
        for(;;) {
            Object s = PORT_STATE.get(port);
            if (s instanceof Reader) {
                Reader reader = (Reader) s;
                Object t = READER_STATE.get(reader);
                if (t == zero) {
                    if (READER_STATE.compareAndSet(reader, t, x)) {
                        reader.step.invoke();
                        break;
                    }
                } else {
                    if (t instanceof Frozen) break;
                    if (READER_STATE.compareAndSet(reader, t, collapse(port, t, x))) break;
                }
            } else {
                if (s instanceof Frozen) break;
                if (PORT_STATE.compareAndSet(port, s, collapse(port, s, x))) break;
            }

        }
    }

    static Object spawn(Port port, IFn step, IFn done) {
        step.invoke();
        for(;;) {
            Object s = PORT_STATE.get(port);
            if (s instanceof Reader) return new Call(done, concurrent);
            else if (s instanceof Frozen) return new Call(done, (Frozen) s);
            else {
                Reader r = new Reader(port, step, done, s);
                if (PORT_STATE.compareAndSet(port, s, r)) return r;
            }
        }
    }

    static Object accumulate(Reader reader, Object y) {
        Object x = reader.last;
        return x == zero ? y : reader.port.sg.invoke(x, y);
    }

    static void cancel(Reader reader) {
        for(;;) {
            Object t = READER_STATE.get(reader);
            if (t == over) break;
            if (READER_STATE.compareAndSet(reader, t, over)) {
                if (t == zero) {
                    terminate(reader, reader.last);
                    reader.step.invoke();
                } else try {
                    terminate(reader, t instanceof Frozen ?
                            new Frozen(false, accumulate(reader, ((Frozen) t).invoke())) :
                            accumulate(reader, t));
                } catch (Throwable e) {
                    terminate(reader, new Frozen(true, e));
                }
                break;
            }
        }
    }

    static Object transfer(Reader reader) {
        for(;;) {
            Object t = READER_STATE.get(reader);
            if (t == over) {
                reader.done.invoke();
                return clojure.lang.Util.sneakyThrow(new Cancelled("Store reader cancelled."));
            } else try {
                if (t instanceof Frozen) {
                    Object x = ((Frozen) t).invoke();
                    Object r = accumulate(reader, x);
                    if (READER_STATE.compareAndSet(reader, t, over)) {
                        terminate(reader, new Frozen(false, r));
                        reader.done.invoke();
                        return x;
                    }
                } else {
                    Object r = accumulate(reader, t);
                    if (READER_STATE.compareAndSet(reader, t, zero)) {
                        reader.last = r;
                        return t;
                    }
                }
            } catch (Throwable e) {
                if (READER_STATE.compareAndSet(reader, t, over)) {
                    terminate(reader, new Frozen(true, e));
                    reader.done.invoke();
                    return clojure.lang.Util.sneakyThrow(e);
                }
            }
        }
    }

    static Object make(IFn sg, Object init) {
        return new Port(sg, init);
    }
}
