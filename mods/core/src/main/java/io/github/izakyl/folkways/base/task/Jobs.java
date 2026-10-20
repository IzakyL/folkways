package io.github.izakyl.folkways.base.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Bounded, owned work: capture a request on its owner's thread, read files on virtual threads, decode on a fixed
 * CPU pool, and publish back on that owner's executor. No world, entity, container or UI belongs in either worker.
 * Closing a scope or this service cancels its futures immediately and interrupts workers without waiting for them.
 * A cancelled worker retains its admission until it actually exits; ignoring interrupts cannot admit more work.
 */
public final class Jobs implements AutoCloseable {

    public record Limits(int requests, long bytes, int readers, int resources, int workers) {
        public Limits {
            if (requests < 1 || bytes < 1 || readers < 1 || resources < 1 || workers < 1) {
                throw new IllegalArgumentException("job limits must be positive");
            }
        }
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Context context) throws Exception;
    }

    @FunctionalInterface
    public interface Decode<I, O> {
        O run(Context context, I input) throws Exception;
    }

    @FunctionalInterface
    public interface Open<R extends AutoCloseable> {
        R open() throws Exception;
    }

    @FunctionalInterface
    public interface Use<R, T> {
        T use(R resource) throws Exception;
    }

    private final Limits limits;
    private final String name;
    private final Semaphore readers;
    private final Semaphore resources;
    private final ThreadPoolExecutor cpu;
    private final Set<Job<?>> active = ConcurrentHashMap.newKeySet();
    private final AtomicInteger named = new AtomicInteger();
    private volatile boolean closed;
    private long reserved;

    public Jobs(String name, Limits limits) {
        this.name = name;
        this.limits = limits;
        readers = new Semaphore(limits.readers(), true);
        resources = new Semaphore(limits.resources(), true);
        AtomicInteger workers = new AtomicInteger();
        cpu = new ThreadPoolExecutor(limits.workers(), limits.workers(), 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(limits.requests()), runnable -> {
                Thread thread = new Thread(runnable, name + "-cpu-" + workers.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    public Scope scope() {
        return new Scope();
    }

    public record Load(int requests, long bytes, int cpuRunning, int cpuQueued) {
    }

    public synchronized Load load() {
        return new Load(active.size(), reserved, cpu.getActiveCount(), cpu.getQueue().size());
    }

    /**
     * Admission never waits or runs work on the caller. Reserve the request's entire retained-byte budget, including
     * decoded output; Context.read also bounds actual input. Rejections are exceptional futures, not silent drops.
     * The owner executor must keep running for successful publication; otherwise close its scope to cancel it.
     */
    public <I, O> CompletableFuture<O> submit(Scope scope, long bytes, Work<I> read, Decode<I, O> decode,
            Executor owner, Consumer<O> publish) {
        if (scope.service() != this || bytes < 0 || bytes > limits.bytes()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid job scope or byte budget"));
        }
        synchronized (this) {
            long generation = scope.epoch;
            if (closed || !scope.current(generation)) {
                return CompletableFuture.failedFuture(new CancellationException("job scope has closed"));
            }
            if (active.size() >= limits.requests() || bytes > limits.bytes() - reserved) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("job capacity is in use"));
            }
            Job<O> job = new Job<>(scope, generation, bytes);
            active.add(job);
            scope.jobs.add(job);
            reserved += bytes;
            job.thread = Thread.ofVirtual().name(name + "-io-" + named.incrementAndGet()).unstarted(() -> {
                Context context = new Context(job);
                try {
                    context.check();
                    I input;
                    readers.acquire();
                    try {
                        context.check();
                        input = read.run(context);
                    } finally {
                        readers.release();
                    }
                    context.check();
                    FutureTask<O> decoded = new FutureTask<O>(() -> {
                        context.check();
                        return decode.run(context, input);
                    }) {
                        @Override
                        public void run() {
                            try {
                                super.run();
                            } finally {
                                job.cpuEnded.complete(null);
                            }
                        }
                    };
                    synchronized (Jobs.this) {
                        // close() must see either no CPU submission or a task already handed to the executor.
                        // Otherwise shutdownNow could drain a just-enqueued task before either cancellation can
                        // remove it, leaving cpuEnded incomplete even though that task can never run.
                        context.check();
                        job.decoding = decoded;
                        try {
                            cpu.execute(decoded);
                        } catch (RejectedExecutionException rejected) {
                            job.cpuEnded.complete(null);
                            throw rejected;
                        }
                    }
                    context.check();
                    job.output = decoded.get();
                    context.check();
                    owner.execute(() -> {
                        // Scope invalidation and publication share only this short lock, never a file read.
                        synchronized (scope) {
                            try {
                                O output = job.output;
                                context.check();
                                publish.accept(output);
                                job.future.complete(output);
                            } catch (Throwable broken) {
                                job.future.completeExceptionally(broken);
                            }
                        }
                    });
                    // Only this admitted virtual thread waits; ticks, frames and close never wait on a job.
                    job.future.get();
                } catch (InterruptedException | CancellationException cancelled) {
                    job.future.cancel(false);
                } catch (ExecutionException broken) {
                    job.future.completeExceptionally(broken.getCause());
                } catch (Throwable broken) {
                    job.future.completeExceptionally(broken);
                } finally {
                    FutureTask<?> decoding = job.decoding;
                    if (decoding != null) {
                        decoding.cancel(true);
                        if (cpu.remove(decoding)) {
                            job.cpuEnded.complete(null);
                        }
                        // FutureTask cancellation completes before an interrupt-ignoring CPU worker exits. Keep its
                        // byte admission until the actual run ends. This wait is only on the admitted virtual thread.
                        job.cpuEnded.join();
                    }
                    job.decoding = null;
                    job.output = null;
                    job.thread = null;
                    scope.jobs.remove(job);
                    synchronized (Jobs.this) {
                        active.remove(job);
                        reserved -= bytes;
                    }
                }
            });
            job.thread.start();
            return job.future;
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            active.forEach(Job::cancel);
            cpu.shutdownNow();
        }
    }

    /** One window, connection, reload or other owner lifetime; next() invalidates an older request's results. */
    public final class Scope implements AutoCloseable {
        private final Set<Job<?>> jobs = ConcurrentHashMap.newKeySet();
        private volatile long epoch;
        private volatile boolean ended;

        private Scope() {
        }

        private Jobs service() {
            return Jobs.this;
        }

        private boolean current(long generation) {
            return !closed && !ended && generation == epoch;
        }

        public void next() {
            synchronized (this) {
                epoch++;
            }
            jobs.stream().filter(job -> job.generation != epoch).forEach(Job::cancel);
        }

        public boolean isOpen() {
            return !closed && !ended;
        }

        @Override
        public void close() {
            synchronized (this) {
                ended = true;
                epoch++;
            }
            jobs.forEach(Job::cancel);
        }
    }

    public final class Context {
        private final Job<?> job;
        private long readBytes;

        private Context(Job<?> job) {
            this.job = job;
        }

        public void check() {
            if (Thread.currentThread().isInterrupted() || job.future.isCancelled()
                    || !job.scope.current(job.generation)) {
                throw new CancellationException("job was cancelled or replaced");
            }
        }

        /** Bounds simultaneous open resources; both opening and use happen outside monitors on the worker. */
        public <R extends AutoCloseable, T> T resource(Open<R> opener, Use<R, T> use) throws Exception {
            check();
            if (!Thread.currentThread().isVirtual()) {
                throw new IllegalStateException("blocking resources belong on the I/O stage");
            }
            resources.acquire();
            try {
                check();
                try (R resource = opener.open()) {
                    return use.use(resource);
                }
            } finally {
                resources.release();
            }
        }

        /** Reads at most the caller's per-file limit and this request's reserved budget, including growing files. */
        public byte[] read(Path file, int most) throws Exception {
            if (most < 0) {
                throw new IllegalArgumentException("negative file limit");
            }
            int limit = (int) Math.min(Math.min(most, Integer.MAX_VALUE - 1L), job.bytes - readBytes);
            return resource(() -> Files.newInputStream(file), in -> {
                byte[] bytes = in.readNBytes(limit + 1);
                check();
                if (bytes.length > limit) {
                    throw new IOException("file exceeds the job's byte limit: " + file);
                }
                readBytes += bytes.length;
                return bytes;
            });
        }
    }

    private final class Job<T> {
        private final Scope scope;
        private final long generation;
        private final long bytes;
        private volatile Thread thread;
        private volatile FutureTask<?> decoding;
        private final CompletableFuture<Void> cpuEnded = new CompletableFuture<>();
        private volatile T output;
        private final CompletableFuture<T> future = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean interrupt) {
                boolean cancelled = super.cancel(interrupt);
                if (cancelled) {
                    Job.this.interrupt();
                }
                return cancelled;
            }
        };

        private Job(Scope scope, long generation, long bytes) {
            this.scope = scope;
            this.generation = generation;
            this.bytes = bytes;
        }

        private void cancel() {
            future.cancel(true);
        }

        private void interrupt() {
            output = null;
            Thread running = thread;
            if (running != null && running != Thread.currentThread()) {
                running.interrupt();
            }
            FutureTask<?> worker = decoding;
            if (worker != null) {
                worker.cancel(true);
                if (cpu.remove(worker)) {
                    cpuEnded.complete(null);
                }
            }
        }
    }
}
