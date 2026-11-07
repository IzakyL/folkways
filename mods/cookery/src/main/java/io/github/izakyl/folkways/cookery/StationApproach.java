package io.github.izakyl.folkways.cookery;

import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.BlockChanges;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

final class StationApproach implements AutoCloseable {
    private record ReachKey(Stances offered, List<WorldPos> points) {
    }

    private static final ThreadLocal<StationApproach> CURRENT = new ThreadLocal<>();
    private final ServerLevel level;
    private final WorldPos at;
    private final KitchenStation provider;
    private final BlockChanges.Heard listener;
    private final Map<ReachKey, Optional<Stances>> reaching = new HashMap<>();
    private Optional<Stances> stances = Optional.empty();
    private Optional<Workshop> workshop = Optional.empty();
    private Object signature;
    private Object recipes;
    private Set<?> refused;
    private int chunks = -1;
    private boolean dirty = true;

    StationApproach(ServerLevel level, WorldPos at, KitchenStation provider) {
        this.level = level;
        this.at = at;
        this.provider = provider;
        BlockPos cell = at.block(level);
        listener = BlockChanges.listen(level, BoundingBox.fromCorners(
            new BlockPos(cell.getX() - 6, level.getMinBuildHeight(), cell.getZ() - 6),
            new BlockPos(cell.getX() + 6, level.getMaxBuildHeight() - 1, cell.getZ() + 6)), changed -> invalidate());
    }

    Optional<Stances> get() {
        int loaded = loadedChunks();
        if (loaded != chunks) {
            chunks = loaded;
            invalidate();
        }
        if (dirty) {
            dirty = false;
            stances = observe(() -> provider.stances(level, at));
        }
        return stances;
    }

    Optional<Workshop> freeze(Supplier<Optional<Workshop>> calculate) {
        Optional<Object> next = provider.freezeSignature(level, at);
        Object currentRecipes = level.getRecipeManager();
        Set<?> currentRefused = Set.copyOf(FolkwaysVanillaConfig.refusedRecipes());
        if (next.isEmpty() || signature == null || !signature.equals(next.get()) || recipes != currentRecipes
                || !currentRefused.equals(refused)) {
            signature = next.orElse(null);
            recipes = currentRecipes;
            refused = currentRefused;
            workshop = observe(calculate);
        }
        return workshop;
    }

    <T> T observe(Supplier<T> calculate) {
        StationApproach before = CURRENT.get();
        CURRENT.set(this);
        try {
            return calculate.get();
        } finally {
            if (before == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(before);
            }
        }
    }

    static Optional<Stances> reaching(ServerLevel level, Stances offered, List<WorldPos> points) {
        StationApproach current = CURRENT.get();
        if (current == null || current.level != level) {
            return KitchenStation.calculateReaching(level, offered, points);
        }
        ReachKey key = new ReachKey(offered, List.copyOf(points));
        return current.reaching.computeIfAbsent(key,
            ignored -> KitchenStation.calculateReaching(level, offered, points));
    }

    private void invalidate() {
        dirty = true;
        signature = null;
        reaching.clear();
    }

    private int loadedChunks() {
        BlockPos cell = at.block(level);
        int mask = 0;
        int bit = 1;
        for (int x = (cell.getX() - 6) >> 4; x <= (cell.getX() + 6) >> 4; x++) {
            for (int z = (cell.getZ() - 6) >> 4; z <= (cell.getZ() + 6) >> 4; z++) {
                if (level.hasChunk(x, z)) { mask |= bit; }
                bit <<= 1;
            }
        }
        return mask;
    }

    @Override
    public void close() {
        listener.close();
    }
}
