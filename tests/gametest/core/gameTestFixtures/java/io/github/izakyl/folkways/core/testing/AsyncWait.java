package io.github.izakyl.folkways.core.testing;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.minecraft.gametest.framework.GameTestHelper;

public final class AsyncWait {

    public static final int TIMEOUT_TICKS = Integer.MAX_VALUE;

    private AsyncWait() {
    }

    public static Runnable until(GameTestHelper helper, BooleanSupplier ready, String label) {
        return until(helper, ready, label, () -> { });
    }

    private static Runnable until(GameTestHelper helper, BooleanSupplier ready, String label, Runnable cleanup) {
        return new Runnable() {
            private long deadline;
            private boolean timedOut;

            @Override
            public void run() {
                // Unthrottled GameTest ticks can exhaust a tick budget before a worker gets CPU time.
                if (deadline == 0) {
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                }
                if (!timedOut && ready.getAsBoolean()) {
                    return;
                }
                if (!timedOut && System.nanoTime() >= deadline) {
                    timedOut = true;
                    helper.runAfterDelay(0, () -> {
                        try {
                            cleanup.run();
                        } finally {
                            helper.fail(label + " timed out after 30 seconds");
                        }
                    });
                }
                helper.fail("Waiting for " + label);
            }
        };
    }

    /** Reads a completed future from a GameTest sequence, preserving its failure as a test diagnostic. */
    public static <T> T result(GameTestHelper helper, CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            helper.fail(failure.getCause().toString());
            return null;
        }
    }

    public static void succeedWhen(GameTestHelper helper, CompletableFuture<?> future, Runnable assertions,
            Runnable cleanup) {
        helper.startSequence().thenWaitUntil(until(helper, future::isDone, "asynchronous result", cleanup))
                .thenExecute(() -> {
                    try {
                        future.join();
                        assertions.run();
                    } catch (RuntimeException | AssertionError failure) {
                        helper.fail(failure.toString());
                    } finally {
                        cleanup.run();
                    }
                }).thenSucceed();
    }
}
