package missionary.impl;

public interface Heap {

    static int[] create(int cap) {
        return new int[cap + 1];
    }

    static int size(int[] heap) {
        return heap[0];
    }

    static void enqueue(int[] heap, int i) {
        int j = heap[0] + 1;
        heap[0] = j;
        heap[j] = i;
        for(;;) if (j == 1) break; else {
            int p = j >> 1;
            int x = heap[j];
            int y = heap[p];
            if (y < x) break; else {
                heap[p] = x;
                heap[j] = y;
                j = p;
            }
        }
    }

    static int dequeue(int[] heap) {
        int s = heap[0];
        int i = heap[1];
        heap[0] = s - 1;
        heap[1] = heap[s];
        int j = 1;
        for(;;) {
            int l = j << 1;
            if (l < s) {
                int x = heap[j];
                int y = heap[l];
                int r = l + 1;
                if (r < s) {
                    int z = heap[r];
                    if (y < z) if (z < x) {
                        heap[r] = x;
                        heap[j] = z;
                        j = r;
                    } else break; else if (y < x) {
                        heap[l] = x;
                        heap[j] = y;
                        j = l;
                    } else break;
                } else if (y < x) {
                    heap[l] = x;
                    heap[j] = y;
                    j = l;
                } else break;
            } else break;
        }
        return i;
    }
}
