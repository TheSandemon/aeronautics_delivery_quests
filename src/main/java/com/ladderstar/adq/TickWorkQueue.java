package com.ladderstar.adq;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

final class TickWorkQueue<T> {
    private final ConcurrentLinkedQueue<T> pending = new ConcurrentLinkedQueue<>();

    void enqueue(T work) {
        pending.add(work);
    }

    boolean runOne(Consumer<? super T> runner) {
        T work = pending.poll();
        if (work == null) {
            return false;
        }
        runner.accept(work);
        return true;
    }

    void clear() {
        pending.clear();
    }

    int size() {
        return pending.size();
    }
}
