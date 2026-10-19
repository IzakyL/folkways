package io.github.izakyl.folkways.core.api.colony;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.MinecraftServer;

public final class ColonyLifecycle {

    public interface Listener {

        default Runnable relocating(MinecraftServer server, Colony colony, Map<WorldPos, WorldPos> moved) {
            return () -> { };
        }

        default void closed(Colony colony, Closing why) {
        }
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private ColonyLifecycle() {
    }

    public static Runnable listen(Listener listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    public static Runnable prepareRelocation(MinecraftServer server, Colony colony,
            Map<WorldPos, WorldPos> moved) {
        Map<WorldPos, WorldPos> snapshot = Map.copyOf(moved);
        List<Runnable> actions = LISTENERS.stream()
            .map(listener -> listener.relocating(server, colony, snapshot)).toList();
        return () -> actions.forEach(Runnable::run);
    }

    public static void closed(Colony colony, Closing why) {
        LISTENERS.forEach(listener -> listener.closed(colony, why));
    }
}
