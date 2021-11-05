package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import java.util.concurrent.CountDownLatch;

public interface Fiber {
    Object park(IFn t);
    Object swich(IFn f);
    Object fork(Number n, IFn f);
    Object check();
    Object unpark();

    Fiber thread = new Fiber() {
        @Override
        public Object park(IFn t) {
            return new CountDownLatch(1) {
                boolean failed;
                Object result;
                {
                    IFn cancel = (IFn) t.invoke(
                            new AFn() {
                                @Override
                                public Object invoke(Object x) {
                                    failed = false;
                                    result = x;
                                    countDown();
                                    return null;
                                }
                            },
                            new AFn() {
                                @Override
                                public Object invoke(Object x) {
                                    failed = true;
                                    result = x;
                                    countDown();
                                    return null;
                                }
                            });
                    try {await();}
                    catch (InterruptedException _) {
                        cancel.invoke();
                        try {await();} catch (InterruptedException __) {}
                        Thread.currentThread().interrupt();
                    }
                    if (failed) clojure.lang.Util.sneakyThrow((Throwable) result);
                }
            }.result;
        }

        @Override
        public Object swich(IFn flow) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object fork(Number par, IFn flow) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object check() {
            return Thread.currentThread().isInterrupted() ?
                    clojure.lang.Util.sneakyThrow(new Cancelled("Thread interrupted.")) : null;
        }

        @Override
        public Object unpark() {
            throw new UnsupportedOperationException();
        }
    };

    ThreadLocal<Fiber> fiber = ThreadLocal.withInitial(() -> thread);

    static Object current() {
        return fiber.get();
    }

    static Object check(Fiber fiber) {
        return fiber.check();
    }

    static Object park(Fiber fiber, IFn task) {
        return fiber.park(task);
    }

    static Object swich(Fiber fiber, IFn flow) {
        return fiber.swich(flow);
    }

    static Object fork(Fiber fiber, Number par, IFn flow) {
        return fiber.fork(par, flow);
    }

    static Object unpark(Fiber fiber) {
        return fiber.unpark();
    }
}