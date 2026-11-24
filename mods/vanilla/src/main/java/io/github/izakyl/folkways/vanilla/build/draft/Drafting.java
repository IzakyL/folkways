package io.github.izakyl.folkways.vanilla.build.draft;

import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import io.github.izakyl.folkways.core.script.api.Workers;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where drawings run: on threads of their own, over a world taken beforehand (see {@link Pattern#capture}), so
 * neither the server's tick nor the client's frames wait on a pattern. What comes back is handed to the level's
 * own thread by whoever asked.
 *
 * <p>Drawings run within the server's time and memory budgets. Exceeding either budget refuses the drawing.
 */
public final class Drafting {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-patterns");

    private static final int THREADS = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));

    private static final Workers PREPARATION = new Workers("folkways-draft-preparation", THREADS);

    private Drafting() {
    }

    public static <T> CompletableFuture<T> prepare(Supplier<T> preparation) {
        return PREPARATION.prepare(preparation);
    }

    public static CompletableFuture<Drawn> draw(Pattern pattern, Commission commission) {
        int seconds = FolkwaysVanillaConfig.drawSeconds();
        try {
            return pattern.submitDrawing(commission, seconds)
                .exceptionally(failure -> failed(pattern, ScriptRuntime.fault(failure), seconds));
        } catch (ScriptFault busy) {
            return CompletableFuture.completedFuture(failed(pattern, busy, seconds));
        }
    }

    static void checkTime() {
        ScriptRuntime.checkTime();
    }

    private static Drawn failed(Pattern pattern, ScriptFault fault, int seconds) {
        if (fault.kind() == ScriptFault.Kind.TIMEOUT) {
            LOGGER.warn("{}: stopped after {} s", pattern.id(), seconds);
            return Drawn.refused(DraftRefusal.TIMED_OUT, Integer.toString(seconds));
        }
        if (fault.kind() == ScriptFault.Kind.MEMORY) {
            return Drawn.refused(DraftRefusal.MEMORY_LIMIT, fault.detail());
        }
        LOGGER.error("{}: drawing failed", pattern.id(), fault);
        return Drawn.refused(DraftRefusal.PATTERN_FAILED, fault.detail());
    }
}
