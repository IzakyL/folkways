package io.github.izakyl.folkways.vanilla.build.draft;

import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/** The overview's client drawing entry; capture stays on the level thread. */
public final class RecipeDrawing {

    private RecipeDrawing() {
    }

    public static CompletableFuture<Drawn> draw(Level level, Pattern pattern, Hint hint, CompoundTag settings,
            Direction facing) {
        return draw(pattern, hint, settings, facing, pattern.capture(level, hint));
    }

    public static CompletableFuture<Drawn> draw(Pattern pattern, Hint hint, CompoundTag settings,
            Direction facing, World snapshot) {
        return Drafting.draw(pattern, new Commission(hint, Chosen.load(pattern.knobs(), settings), snapshot, facing,
            Patterns.seedOf(pattern, hint)));
    }
}
