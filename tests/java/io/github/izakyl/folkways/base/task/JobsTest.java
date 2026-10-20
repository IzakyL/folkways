package io.github.izakyl.folkways.base.task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Standalone Java 21 behavioural tests; no Minecraft, player files or dependency downloads. */
public final class JobsTest {

    public static void main(String[] args) throws Exception {
        virtualIoBoundedCpuResourcesAndOwner();
        requestAndByteBackpressure();
        staleResultsAndScopeReuse();
        cancellationKeepsAdmissionUntilWorkerActuallyExits();
        closeCompletesPendingPublicationAndDoesNotWait();
        readBoundsAndOwnerRejection();
        closeRacingCpuEnqueueReleasesAdmission();
        System.out.println("JobsTest: 7 behavioural cases passed");
    }

    private static void virtualIoBoundedCpuResourcesAndOwner() throws Exception {
        Path file = Files.createTempFile("folkways-jobs-test-", ".txt");
        Files.writeString(file, "snapshot");
        CountDownLatch resourcesOpened = new CountDownLatch(1);
        CountDownLatch letRead = new CountDownLatch(1);
        CountDownLatch cpuStarted = new CountDownLatch(2);
        CountDownLatch letDecode = new CountDownLatch(1);
        AtomicInteger resources = new AtomicInteger();
        AtomicInteger resourcePeak = new AtomicInteger();
        AtomicInteger workers = new AtomicInteger();
        AtomicInteger workerPeak = new AtomicInteger();
        AtomicInteger published = new AtomicInteger();
        Thread owner = Thread.currentThread();
        BlockingQueue<Runnable> ready = new LinkedBlockingQueue<>();
        List<CompletableFuture<String>> futures = new ArrayList<>();
        try (Jobs jobs = new Jobs("jobs-test", new Jobs.Limits(4, 400, 2, 1, 2))) {
            Jobs.Scope scope = jobs.scope();
            for (int at = 0; at < 4; at++) {
                futures.add(jobs.submit(scope, 100, context -> {
                    check(Thread.currentThread().isVirtual(), "I/O must run on a virtual thread");
                    context.resource(() -> {
                        resourcePeak.accumulateAndGet(resources.incrementAndGet(), Math::max);
                        resourcesOpened.countDown();
                        return (AutoCloseable) resources::decrementAndGet;
                    }, resource -> {
                        await(letRead);
                        return null;
                    });
                    return context.read(file, 20);
                }, (context, bytes) -> {
                    check(!Thread.currentThread().isVirtual(), "CPU workers must remain bounded platform threads");
                    workerPeak.accumulateAndGet(workers.incrementAndGet(), Math::max);
                    cpuStarted.countDown();
                    try {
                        await(letDecode);
                        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    } finally {
                        workers.decrementAndGet();
                    }
                }, ready::add, result -> {
                    check(Thread.currentThread() == owner, "publish must return to the owner executor");
                    check(result.equals("snapshot"), "the bytes read are the bytes decoded");
                    published.incrementAndGet();
                }));
            }
            await(resourcesOpened);
            check(resourcePeak.get() == 1, "only one resource permit is available");
            letRead.countDown();
            await(cpuStarted);
            check(workerPeak.get() == 2, "two CPU workers can run, with the rest queued");
            check(published.get() == 0, "workers cannot publish to the owner directly");
            letDecode.countDown();
            for (int at = 0; at < 4; at++) {
                take(ready).run();
            }
            for (CompletableFuture<String> future : futures) {
                check(future.get(5, TimeUnit.SECONDS).equals("snapshot"), "publication completes every future");
            }
            until(() -> jobs.load().requests() == 0);
            check(resourcePeak.get() == 1 && workerPeak.get() == 2, "resource and CPU limits hold over the whole batch");
            check(resources.get() == 0 && jobs.load().bytes() == 0, "resources and reservations are released");
        } finally {
            letRead.countDown();
            letDecode.countDown();
            Files.deleteIfExists(file);
        }
    }

    private static void requestAndByteBackpressure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Jobs jobs = new Jobs("admission-test", new Jobs.Limits(1, 16, 1, 1, 1))) {
            jobs.submit(jobs.scope(), 8, context -> {
                entered.countDown();
                await(release);
                return 1;
            }, (context, input) -> input, Runnable::run, value -> { });
            await(entered);
            expect(jobs.submit(jobs.scope(), 1, context -> 1, (context, input) -> input,
                Runnable::run, value -> { }), RejectedExecutionException.class);
        } finally {
            release.countDown();
        }
        CountDownLatch hold = new CountDownLatch(1);
        try (Jobs jobs = new Jobs("bytes-test", new Jobs.Limits(4, 16, 1, 1, 1))) {
            jobs.submit(jobs.scope(), 12, context -> {
                await(hold);
                return 1;
            }, (context, input) -> input, Runnable::run, value -> { });
            expect(jobs.submit(jobs.scope(), 8, context -> 1, (context, input) -> input,
                Runnable::run, value -> { }), RejectedExecutionException.class);
            check(jobs.load().bytes() == 12, "rejected requests retain no byte reservation");
        } finally {
            hold.countDown();
        }
    }

    private static void staleResultsAndScopeReuse() throws Exception {
        BlockingQueue<Runnable> ready = new LinkedBlockingQueue<>();
        AtomicInteger published = new AtomicInteger();
        try (Jobs jobs = new Jobs("epoch-test", new Jobs.Limits(2, 16, 1, 1, 1))) {
            Jobs.Scope scope = jobs.scope();
            CompletableFuture<Integer> old = jobs.submit(scope, 8, context -> 1, (context, input) -> input,
                ready::add, published::set);
            Runnable stale = take(ready);
            scope.next();
            check(old.isCancelled(), "changing epoch explicitly completes the old future");
            stale.run();
            check(published.get() == 0, "an already queued owner callback cannot publish an old epoch");
            until(() -> jobs.load().requests() == 0);
            CompletableFuture<Integer> current = jobs.submit(scope, 8, context -> 2, (context, input) -> input,
                ready::add, published::set);
            take(ready).run();
            check(current.get(5, TimeUnit.SECONDS) == 2 && published.get() == 2, "the next epoch can publish");
        }
    }

    private static void cancellationKeepsAdmissionUntilWorkerActuallyExits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Jobs jobs = new Jobs("cancel-test", new Jobs.Limits(1, 16, 1, 1, 1))) {
            Jobs.Scope scope = jobs.scope();
            CompletableFuture<Integer> work = jobs.submit(scope, 16, context -> 1, (context, input) -> {
                entered.countDown();
                // Deliberately ignores interrupts: cancelling its Future must not free the retained-byte budget.
                while (release.getCount() != 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                    }
                }
                return input;
            }, Runnable::run, value -> { throw new AssertionError("cancelled work was published"); });
            await(entered);
            scope.close();
            check(work.isCancelled(), "closing a scope ends its future without waiting for the CPU worker");
            check(jobs.load().requests() == 1 && jobs.load().bytes() == 16,
                "the still-running worker keeps both admissions");
            expect(jobs.submit(jobs.scope(), 1, context -> 1, (context, input) -> input,
                Runnable::run, value -> { }), RejectedExecutionException.class);
            release.countDown();
            until(() -> jobs.load().requests() == 0);
            check(jobs.load().bytes() == 0, "actual worker exit returns its reservation");
        } finally {
            release.countDown();
        }
    }

    private static void closeCompletesPendingPublicationAndDoesNotWait() throws Exception {
        BlockingQueue<Runnable> ready = new LinkedBlockingQueue<>();
        Jobs jobs = new Jobs("close-test", new Jobs.Limits(1, 16, 1, 1, 1));
        CompletableFuture<Integer> pending = jobs.submit(jobs.scope(), 16, context -> 1,
            (context, input) -> input, ready::add, value -> { throw new AssertionError("closed service published"); });
        Runnable obsolete = take(ready);
        jobs.close();
        check(pending.isCancelled(), "close completes a future even when its owner no longer drains callbacks");
        obsolete.run();
        until(() -> jobs.load().requests() == 0);
        check(jobs.load().bytes() == 0, "closed pending publications release retained output");
    }

    private static void readBoundsAndOwnerRejection() throws Exception {
        Path file = Files.createTempFile("folkways-jobs-limit-", ".txt");
        Files.writeString(file, "growing-file");
        try (Jobs jobs = new Jobs("failure-test", new Jobs.Limits(2, 32, 1, 1, 1))) {
            expect(jobs.submit(jobs.scope(), 16, context -> context.read(file, 3), (context, input) -> input,
                Runnable::run, value -> { }), java.io.IOException.class);
            until(() -> jobs.load().requests() == 0);
            expect(jobs.submit(jobs.scope(), 16, context -> context.read(file, 16), (context, input) -> input,
                task -> { throw new RejectedExecutionException("owner ended"); }, value -> { }),
                RejectedExecutionException.class);
            until(() -> jobs.load().requests() == 0);
            check(jobs.load().bytes() == 0, "I/O errors and owner rejection cannot leak admission");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void closeRacingCpuEnqueueReleasesAdmission() throws Exception {
        Jobs jobs = new Jobs("enqueue-close-test", new Jobs.Limits(1, 16, 1, 1, 1));
        EnqueueGate executor = new EnqueueGate();
        CountDownLatch cpuOccupied = new CountDownLatch(1);
        CountDownLatch letCpuExit = new CountDownLatch(1);
        CountDownLatch closeDone = new CountDownLatch(1);
        Thread closer = null;
        try {
            // Occupy the only worker, so the tested task must enqueue instead of running immediately. This worker
            // belongs to the test, not Jobs, and ignores shutdown's interrupt until the interleaving is complete.
            executor.execute(() -> {
                cpuOccupied.countDown();
                awaitUninterruptibly(letCpuExit);
            });
            await(cpuOccupied);
            var field = Jobs.class.getDeclaredField("cpu");
            field.setAccessible(true);
            ((ThreadPoolExecutor) field.get(jobs)).shutdownNow();
            field.set(jobs, executor);
            executor.gated = true;
            CompletableFuture<Integer> future = jobs.submit(jobs.scope(), 16, context -> 1,
                (context, input) -> input, Runnable::run,
                value -> { throw new AssertionError("a task racing close was published"); });
            await(executor.entered);
            closer = new Thread(() -> {
                try {
                    jobs.close();
                } finally {
                    closeDone.countDown();
                }
            }, "jobs-test-close");
            closer.setDaemon(true);
            closer.start();
            Thread closing = closer;
            until(() -> executor.shuttingDown.getCount() == 0 || closing.getState() == Thread.State.BLOCKED);
            if (executor.shuttingDown.getCount() == 0) {
                // Regression schedule: close cancelled the not-yet-enqueued FutureTask, then paused before its
                // shutdownNow. Enqueue that cancelled task, let shutdown drain it, then let the submitting VT unwind.
                executor.letEnqueue.countDown();
                await(executor.enqueued);
                executor.letShutdown.countDown();
                await(closeDone);
                executor.letExecuteReturn.countDown();
            } else {
                // With submission and close ordered together, close cannot cancel before execute has returned.
                executor.letEnqueue.countDown();
                await(executor.enqueued);
                executor.letExecuteReturn.countDown();
                await(executor.shuttingDown);
                executor.letShutdown.countDown();
                await(closeDone);
            }
            check(future.isCancelled(), "close ends the public future in either interleaving");
            until(() -> jobs.load().requests() == 0);
            check(jobs.load().bytes() == 0, "a drained CPU task cannot retain its virtual thread and byte admission");
        } finally {
            executor.letEnqueue.countDown();
            executor.letExecuteReturn.countDown();
            executor.letShutdown.countDown();
            letCpuExit.countDown();
            jobs.close();
            executor.shutdownNow();
            if (closer != null) {
                closer.join(5_000);
            }
        }
    }

    /** Controls executor hand-off and shutdown without adding scheduling hooks to production code. */
    private static final class EnqueueGate extends ThreadPoolExecutor {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch letEnqueue = new CountDownLatch(1);
        private final CountDownLatch enqueued = new CountDownLatch(1);
        private final CountDownLatch letExecuteReturn = new CountDownLatch(1);
        private final CountDownLatch shuttingDown = new CountDownLatch(1);
        private final CountDownLatch letShutdown = new CountDownLatch(1);
        private volatile boolean gated;

        private EnqueueGate() {
            super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), runnable -> {
                Thread thread = new Thread(runnable, "jobs-test-gated-cpu");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        public void execute(Runnable task) {
            if (!gated) {
                super.execute(task);
                return;
            }
            entered.countDown();
            awaitUninterruptibly(letEnqueue);
            super.execute(task);
            enqueued.countDown();
            awaitUninterruptibly(letExecuteReturn);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shuttingDown.countDown();
            awaitUninterruptibly(letShutdown);
            return super.shutdownNow();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (latch.getCount() != 0) {
                try {
                    check(latch.await(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS),
                        "interleaving gate timed out");
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void expect(CompletableFuture<?> future, Class<? extends Throwable> type) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected " + type.getSimpleName());
        } catch (ExecutionException broken) {
            check(type.isInstance(broken.getCause()), "wrong failure: " + broken.getCause());
        }
    }

    private static Runnable take(BlockingQueue<Runnable> queue) throws Exception {
        Runnable next = queue.poll(5, TimeUnit.SECONDS);
        check(next != null, "owner publication timed out");
        return next;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), "worker gate timed out");
    }

    private static void until(BooleanSupplier condition) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            LockSupport.parkNanos(100_000);
        }
        check(condition.getAsBoolean(), "cleanup timed out");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
