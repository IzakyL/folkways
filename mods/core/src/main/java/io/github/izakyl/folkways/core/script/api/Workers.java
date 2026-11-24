package io.github.izakyl.folkways.core.script.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class Workers {

    // Scripts may recurse, and deeper than a thread's usual stack lets them.
    private static final long STACK_BYTES = 16L << 20;

    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "folkways-script-clock");
        thread.setDaemon(true);
        return thread;
    });

    private static final ThreadLocal<Watch> CURRENT = new ThreadLocal<>();

    private final ExecutorService pool;

    private final Semaphore slots;

    public Workers(String prefix, int threads) {
        this(prefix, threads, Integer.MAX_VALUE);
    }

    Workers(String prefix, int threads, int maxInFlight) {
        slots = new Semaphore(maxInFlight);
        AtomicInteger named = new AtomicInteger();
        pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(null, runnable, prefix + "-" + named.incrementAndGet(), STACK_BYTES);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    /**
     * Stops work whose time is up. Starlark looks for this between statements of its own; the long loops a
     * script runs in Java call it as they go.
     */
    public static void checkTime() {
        if (Thread.currentThread().isInterrupted()) {
            throw new TimedOut();
        }
    }

    public <T> CompletableFuture<T> prepare(Supplier<T> preparation) {
        return CompletableFuture.supplyAsync(preparation, pool);
    }

    public <T> CompletableFuture<Result<T>> submit(Supplier<T> work, long time, TimeUnit unit) {
        if (!slots.tryAcquire()) {
            throw new RejectedExecutionException("worker lane is busy");
        }
        return CompletableFuture.<Result<T>>supplyAsync(() -> {
            Watch watch = new Watch(Thread.currentThread(), unit.toNanos(time));
            CURRENT.set(watch);
            watch.resume();
            Result<T> result;
            try {
                result = new Done<>(work.get());
            } catch (RuntimeException | StackOverflowError broken) {
                result = watch.rang() ? null : new Failed<>(broken);
            } finally {
                watch.stop();
                CURRENT.remove();
                // An interrupt that came as the work ended is not carried into the next one.
                Thread.interrupted();
            }
            if (watch.rang()) {
                return new Timeout<>();
            }
            return result;
        }, pool).whenComplete((result, failure) -> slots.release());
    }

    /** Gives a bounded external wait its own budget while preserving the remaining script time. */
    public static <T> T awaitExternal(long time, TimeUnit unit, Interruptible<T> wait) throws InterruptedException {
        Watch watch = CURRENT.get();
        if (watch == null) {
            return wait.get();
        }
        watch.suspend(unit.toNanos(time));
        try {
            return wait.get();
        } finally {
            watch.resume();
        }
    }

    @FunctionalInterface
    public interface Interruptible<T> {
        T get() throws InterruptedException;
    }

    public sealed interface Result<T> permits Done, Failed, Timeout {
    }

    public record Done<T>(T value) implements Result<T> {
    }

    public record Failed<T>(Throwable cause) implements Result<T> {
    }

    public record Timeout<T>() implements Result<T> {
    }

    // Interrupts the working thread if its time comes while it is still working, and never once it has stopped.
    private static final class Watch {

        private final Thread working;
        private ScheduledFuture<?> alarm;
        private long deadline;
        private long remaining;
        private int generation;
        private boolean rang;

        private Watch(Thread working, long remaining) {
            this.working = working;
            this.remaining = remaining;
        }

        private synchronized void schedule(long nanos) {
            int token = ++generation;
            deadline = System.nanoTime() + nanos;
            alarm = CLOCK.schedule(() -> ring(token), nanos, TimeUnit.NANOSECONDS);
        }

        private synchronized void ring(int token) {
            if (token == generation) {
                rang = true;
                working.interrupt();
            }
        }

        synchronized void suspend(long nanos) {
            remaining = Math.max(0, deadline - System.nanoTime());
            stop();
            schedule(remaining == 0 ? 0 : nanos);
        }

        synchronized void resume() {
            stop();
            if (!rang) {
                schedule(remaining);
            }
        }

        synchronized void stop() {
            generation++;
            if (alarm != null) {
                alarm.cancel(false);
                alarm = null;
            }
        }

        synchronized boolean rang() {
            return rang;
        }
    }

    public static final class TimedOut extends RuntimeException {

        public TimedOut() {
            super("out of time", null, false, false);
        }
    }
}
