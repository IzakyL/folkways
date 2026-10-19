package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where drawings run: on threads of their own, over a world taken beforehand (see {@link Pattern#capture}), so
 * neither the server's tick nor the client's frames wait on a pattern. What comes back is handed to the level's
 * own thread by whoever asked.
 *
 * <p>A pattern is trusted to draw what it likes, as far as it captured; the one thing held over it is time. Once
 * a drawing has run for as long as the server allows, its thread is interrupted, and it comes back refused.
 */
public final class Drafting {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-patterns");

    private static final int THREADS = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));

    // Patterns may recurse, and deeper than a thread's usual stack lets them.
    private static final long STACK_BYTES = 16L << 20;

    private static final ExecutorService POOL;

    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "folkways-draft-clock");
        thread.setDaemon(true);
        return thread;
    });

    static {
        AtomicInteger named = new AtomicInteger();
        POOL = Executors.newFixedThreadPool(THREADS, runnable -> {
            Thread thread = new Thread(null, runnable, "folkways-draft-" + named.incrementAndGet(), STACK_BYTES);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    private Drafting() {
    }

    public static CompletableFuture<Drawn> draw(Pattern pattern, Commission commission) {
        return run(pattern, () -> pattern.drawOn(commission), Function.identity());
    }

    public static CompletableFuture<Round> grow(Pattern pattern, Commission commission) {
        return run(pattern, () -> pattern.growOn(commission), refused -> new Round.Stuck((Drawn.Refused) refused));
    }

    /**
     * Stops a drawing whose time is up. Starlark looks for this between statements of its own; the long loops a
     * drawing runs in Java call it as they go.
     */
    static void checkTime() {
        if (Thread.currentThread().isInterrupted()) {
            throw new TimedOut();
        }
    }

    // Whatever a drawing throws comes back as the pattern having failed, never as a future left hanging.
    private static <T> CompletableFuture<T> run(Pattern pattern, Supplier<T> drawing,
            Function<Drawn, T> refused) {
        int seconds = FolkwaysVanillaConfig.drawSeconds();
        return CompletableFuture.supplyAsync(() -> {
            Watch watch = new Watch(Thread.currentThread());
            ScheduledFuture<?> alarm = CLOCK.schedule(watch::ring, seconds, TimeUnit.SECONDS);
            T drawn;
            try {
                drawn = drawing.get();
            } catch (RuntimeException | StackOverflowError broken) {
                drawn = watch.rang() ? null : refused.apply(failed(pattern, broken));
            } finally {
                alarm.cancel(false);
                watch.stop();
                // An interrupt that came as the drawing ended is not carried into the next one.
                Thread.interrupted();
            }
            if (watch.rang()) {
                LOGGER.warn("{}: stopped after {} s", pattern.id(), seconds);
                return refused.apply(Drawn.refused(DraftRefusal.TIMED_OUT, Integer.toString(seconds)));
            }
            return drawn;
        }, POOL);
    }

    private static Drawn failed(Pattern pattern, Throwable broken) {
        LOGGER.error("{}: drawing failed", pattern.id(), broken);
        return Drawn.refused(DraftRefusal.PATTERN_FAILED, String.valueOf(broken.getMessage()));
    }

    // Interrupts the drawing's thread if its time comes while it is still drawing, and never once it has stopped.
    private static final class Watch {

        private final Thread drawing;
        private boolean running = true;
        private boolean rang;

        Watch(Thread drawing) {
            this.drawing = drawing;
        }

        synchronized void ring() {
            if (running) {
                rang = true;
                drawing.interrupt();
            }
        }

        synchronized void stop() {
            running = false;
        }

        synchronized boolean rang() {
            return rang;
        }
    }

    static final class TimedOut extends RuntimeException {

        TimedOut() {
            super("out of time", null, false, false);
        }
    }
}
