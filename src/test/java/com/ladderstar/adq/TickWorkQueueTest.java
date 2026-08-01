package com.ladderstar.adq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TickWorkQueueTest {
    @Test
    void reentrantEnqueueWaitsForTheNextDrain() {
        TickWorkQueue<Runnable> queue = new TickWorkQueue<>();
        List<String> completed = new ArrayList<>();

        queue.enqueue(() -> {
            completed.add("first");
            queue.enqueue(() -> completed.add("second"));
        });

        assertTrue(queue.runOne(Runnable::run));
        assertEquals(List.of("first"), completed);
        assertEquals(1, queue.size());

        assertTrue(queue.runOne(Runnable::run));
        assertEquals(List.of("first", "second"), completed);
        assertFalse(queue.runOne(Runnable::run));
    }

    @Test
    void clearRemovesPendingWork() {
        TickWorkQueue<Runnable> queue = new TickWorkQueue<>();
        queue.enqueue(() -> {});
        queue.enqueue(() -> {});

        queue.clear();

        assertEquals(0, queue.size());
        assertFalse(queue.runOne(Runnable::run));
    }
}
