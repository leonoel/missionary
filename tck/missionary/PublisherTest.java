package missionary;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;

@SuppressWarnings("unchecked")
public final class PublisherTest extends PublisherVerification<Object> {

    public PublisherTest() {
        super(new TestEnvironment());
    }

    @Override
    public Publisher<Object> createPublisher(final long n) {
        Clojure.var("clojure.core", "require").invoke(Clojure.read("missionary.core"));
        IFn range = Clojure.var("clojure.core", "range");
        IFn publisher = Clojure.var("missionary.core", "publisher");
        IFn enumerate = Clojure.var("missionary.core", "seed");
        return (Publisher<Object>) publisher.invoke(enumerate.invoke(range.invoke(n)));
    }

    @Override
    public Publisher<Object> createFailedPublisher() {
        return null;
    }

}
