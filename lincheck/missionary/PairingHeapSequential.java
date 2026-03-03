package missionary;

import java.util.*;

public class PairingHeapSequential {
    private final List<Integer> elements = new ArrayList<>();

    public void insert(int id) {
        elements.add(id);
    }

    public List<Integer> accept() {
        List<Integer> result = new ArrayList<>(elements);
        Collections.sort(result);
        elements.clear();
        return result;
    }
}
