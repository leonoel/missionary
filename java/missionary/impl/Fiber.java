package missionary.impl;

import clojure.lang.*;
import missionary.Cancelled;

import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

public interface Fiber {
    ThreadLocal<Fiber> CURRENT = ThreadLocal.withInitial(new Supplier<Fiber>() {
        Fiber THREAD = new Fiber() {
            @Override
            public Object poll() {
                return Thread.currentThread().isInterrupted() ?
                        clojure.lang.Util.sneakyThrow(new Cancelled("Thread interrupted.")) : null;
            }

            @Override
            public Object task(IFn t) {
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
            public Object flowConcat(IFn f) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object flowSwitch(IFn f) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object flowGather(IFn f) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object unpark() {
                throw new UnsupportedOperationException();
            }
        };

        @Override
        public Fiber get() {
            return THREAD;
        }
    });

    Object poll();
    Object task(IFn t);
    Object flowConcat(IFn f);
    Object flowSwitch(IFn f);
    Object flowGather(IFn f);
    Object unpark();
}
