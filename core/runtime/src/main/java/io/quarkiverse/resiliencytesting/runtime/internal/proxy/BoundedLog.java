package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import java.util.ArrayDeque;
import java.util.List;

/**
 * The most recent {@code capacity} entries, oldest first. Used on the event loop only.
 */
final class BoundedLog<T> {

    private final int capacity;
    private final ArrayDeque<T> entries;

    BoundedLog(int capacity) {
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(Math.max(0, capacity));
    }

    void add(T entry) {
        if (capacity <= 0) {
            return;
        }
        while (entries.size() >= capacity) {
            entries.pollFirst();
        }
        entries.addLast(entry);
    }

    List<T> snapshot() {
        return List.copyOf(entries);
    }
}
