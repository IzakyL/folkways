package io.github.izakyl.folkways.core.api.ground;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Hears every block set in a box of a level, on the server thread, as it is set. A listener is told inside the
 * setting itself, so it notes the cell and does its work later rather than touching the world there.
 */
public final class BlockChanges {

    public interface Heard extends AutoCloseable {

        @Override
        void close();
    }

    private record Listener(BoundingBox box, Consumer<BlockPos> told) {
    }

    private static final Map<ServerLevel, List<Listener>> LISTENING = new WeakHashMap<>();

    private BlockChanges() {
    }

    public static Heard listen(ServerLevel level, BoundingBox box, Consumer<BlockPos> told) {
        Listener listener = new Listener(box, told);
        LISTENING.computeIfAbsent(level, ignored -> new ArrayList<>()).add(listener);
        return () -> {
            List<Listener> listeners = LISTENING.get(level);
            if (listeners != null && listeners.remove(listener) && listeners.isEmpty()) {
                LISTENING.remove(level);
            }
        };
    }

    public static void changed(ServerLevel level, BlockPos cell) {
        List<Listener> listeners = LISTENING.get(level);
        if (listeners == null) {
            return;
        }
        for (Listener listener : List.copyOf(listeners)) {
            if (listener.box().isInside(cell)) {
                listener.told().accept(cell.immutable());
            }
        }
    }
}
