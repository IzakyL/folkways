package io.github.izakyl.folkways.core.engine.labor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

final class Solves {

    private static final long DRAIN_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static final long WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final ExecutorService pool;

    private final Map<UUID, Queue> byColony = new ConcurrentHashMap<>();

    Solves(int threads) {
        AtomicInteger named = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "folkways-solve-" + named.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    Executor of(UUID colony) {
        return byColony.computeIfAbsent(colony, id -> new Queue(pool));
    }

    void forget(UUID colony) {
        byColony.remove(colony);
    }

    void shutdown() {
        long until = System.nanoTime() + DRAIN_NANOS;
        while (byColony.values().stream().anyMatch(queue -> !queue.idle()) && System.nanoTime() < until) {
            LockSupport.parkNanos(WAIT_NANOS);
        }
        byColony.clear();
        pool.shutdownNow();
    }

    private static final class Queue implements Executor {

        private final Executor pool;
        private final Deque<Runnable> waiting = new ArrayDeque<>();

        private boolean running;

        Queue(Executor pool) {
            this.pool = pool;
        }

        @Override
        public void execute(Runnable task) {
            Runnable next;
            synchronized (this) {
                waiting.addLast(() -> {
                    try {
                        task.run();
                    } finally {
                        done();
                    }
                });
                if (running) {
                    return;
                }
                running = true;
                next = waiting.pollFirst();
            }
            hand(next);
        }

        synchronized boolean idle() {
            return !running && waiting.isEmpty();
        }

        private void done() {
            Runnable next;
            synchronized (this) {
                next = waiting.pollFirst();
                running = next != null;
            }
            if (next != null) {
                hand(next);
            }
        }

        private void hand(Runnable task) {
            try {
                pool.execute(task);
            } catch (RejectedExecutionException gone) {
                synchronized (this) {
                    waiting.clear();
                    running = false;
                }
            }
        }
    }
}
