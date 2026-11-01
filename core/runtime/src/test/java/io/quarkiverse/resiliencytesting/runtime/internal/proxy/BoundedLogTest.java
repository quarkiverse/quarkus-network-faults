package io.quarkiverse.resiliencytesting.runtime.internal.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class BoundedLogTest {

    @Test
    void keepsAtMostCapacityEntriesOldestFirst() {
        BoundedLog<Integer> log = new BoundedLog<>(3);
        for (int i = 1; i <= 5; i++) {
            log.add(i);
        }
        assertEquals(List.of(3, 4, 5), log.snapshot());
    }

    @Test
    void zeroCapacityHoldsNothing() {
        BoundedLog<Integer> log = new BoundedLog<>(0);
        for (int i = 1; i <= 5; i++) {
            log.add(i);
        }
        // before the fix the size == capacity guard never evicted after the first add, so the log grew unbounded
        assertEquals(List.of(), log.snapshot());
    }
}
