package io.github.izakyl.folkways.front.api;

import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/** Optional display-only reconstruction supplied by a drafting plugin. */
public final class GhostRecipes {

    public interface Renderer {
        void accept(List<CompoundTag> recipes, List<UUID> standing);

        List<Ghost> show(Level level, List<Ghost> ghosts);

        List<UUID> unavailable();

        void clear();
    }

    private static Renderer renderer;

    private GhostRecipes() {
    }

    public static void install(Renderer installed) {
        renderer = installed;
    }

    public static void accept(List<CompoundTag> recipes, List<UUID> standing) {
        if (renderer != null) {
            renderer.accept(recipes, standing);
        }
    }

    public static List<Ghost> show(Level level, List<Ghost> ghosts) {
        return renderer == null ? ghosts : renderer.show(level, ghosts);
    }

    public static List<UUID> unavailable() {
        return renderer == null ? List.of() : renderer.unavailable();
    }

    public static void clear() {
        if (renderer != null) {
            renderer.clear();
        }
    }
}
