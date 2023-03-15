package missionary;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.tck.SubscriberWhiteboxVerification;
import org.reactivestreams.tck.TestEnvironment;

public final class SubscriberTest extends SubscriberWhiteboxVerification<Object> {
    public SubscriberTest() {
        super(new TestEnvironment());
    }

    @Override
    public Subscriber<Object> createSubscriber(final WhiteboxSubscriberProbe<Object> probe) {

        return new Subscriber<Object>() {
            Subscriber<Object> subscriber;

            {
                Clojure.var("clojure.core", "require").invoke(Clojure.read("missionary.core"));
                IFn println = Clojure.var("clojure.core", "println");
                IFn plus = Clojure.var("clojure.core", "+");
                IFn subscribe = Clojure.var("missionary.core", "subscribe");
                IFn sink = Clojure.var ("missionary.core", "reduce");
                IFn task = (IFn) sink.invoke(plus, subscribe.invoke(new Publisher<Object>() {
                    public void subscribe(Subscriber<? super Object> s) {
                        subscriber = s;
                    }
                }));
                task.invoke(println, println);
            }

            public void onSubscribe(final Subscription subscription) {
                subscriber.onSubscribe(subscription);
                probe.registerOnSubscribe(new SubscriberPuppet() {
                    public void triggerRequest(long elements) {
                        subscription.request(elements);
                    }
                    public void signalCancel() {
                        subscription.cancel();
                    }
                });
            }
            public void onNext(Object o) {
                subscriber.onNext(o);
                probe.registerOnNext(o);
            }
            public void onError(Throwable throwable) {
                subscriber.onError(throwable);
                probe.registerOnError(throwable);
            }
            public void onComplete() {
                subscriber.onComplete();
                probe.registerOnComplete();
            }
        };
    }

    @Override
    public Object createElement(int element) {
        return element;
    }

}

