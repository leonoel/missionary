package missionary;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Lincheck test for the concurrent pairing heap.
 *
 * Tests linearizability: can the concurrent results be explained by some
 * sequential ordering of insert/accept operations?
 *
 * The heap protocol is N-writer-1-reader: insert is concurrent, accept is
 * guarded by nonParallelGroup="reader" to enforce single-reader. Accept on
 * an empty heap throws Error which we catch and return null — the heap's own
 * internal state (tail field) handles synchronization with concurrent inserts.
 */
public class PairingHeapLincheckTest {

    private static final IFn HEAP, INSERT_NODE, ACCEPT_AS_VEC;
    static {
        Clojure.var("clojure.core", "require")
            .invoke(Clojure.read("missionary.pairing-heap-test-impl"));
        HEAP = Clojure.var("missionary.pairing-heap-test-impl", "heap");
        INSERT_NODE = Clojure.var("missionary.pairing-heap-test-impl", "insert-node");
        ACCEPT_AS_VEC = Clojure.var("missionary.pairing-heap-test-impl", "accept-as-vec");
    }

    private Object heap;

    public PairingHeapLincheckTest() {
        heap = HEAP.invoke();
    }

    @Operation
    public void insert(int id) {
        INSERT_NODE.invoke(heap, id);
    }

    @Operation(nonParallelGroup = "reader")
    public List<Integer> accept() {
        Object result = ACCEPT_AS_VEC.invoke(heap);
        List<?> v = (List<?>) result;
        List<Integer> out = new ArrayList<>(v.size());
        for (Object o : v) out.add(((Number) o).intValue());
        return out;
    }

    @Test
    public void stressTest() {
        new StressOptions()
            .iterations(50)
            .threads(3)
            .actorsPerThread(4)
            .sequentialSpecification(PairingHeapSequential.class)
            .check(this.getClass());
    }

    @Test
    public void modelCheckingTest() {
        new ModelCheckingOptions()
            .iterations(50)
            .threads(3)
            .actorsPerThread(3)
            .sequentialSpecification(PairingHeapSequential.class)
            .check(this.getClass());
    }
}
