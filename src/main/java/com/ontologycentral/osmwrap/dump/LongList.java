package com.ontologycentral.osmwrap.dump;

import java.util.Arrays;

/** A growable {@code long[]}, without boxing. */
final class LongList {

    private long[] a;
    private int n;

    LongList() {
        this(16);
    }

    LongList(int capacity) {
        a = new long[Math.max(1, capacity)];
    }

    void add(long v) {
        if (n == a.length) {
            a = Arrays.copyOf(a, a.length * 2);
        }
        a[n++] = v;
    }

    long get(int i) {
        if (i >= n) {
            throw new IndexOutOfBoundsException(i + " >= " + n);
        }
        return a[i];
    }

    int size() {
        return n;
    }

    void clear() {
        n = 0;
    }

    /** Last value, or {@code fallback} when empty. */
    long last(long fallback) {
        return n == 0 ? fallback : a[n - 1];
    }

    /** Undo delta coding in place: each value becomes the running sum. */
    void undelta() {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[i];
            a[i] = sum;
        }
    }

    long[] toArray() {
        return Arrays.copyOf(a, n);
    }
}
