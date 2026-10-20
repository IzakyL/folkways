package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.base.Ledger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.level.block.Block;

public final class CropBehaviors {
    private static volatile Map<Block, CropBehavior> registered = Map.of();

    private CropBehaviors() {
    }

    public static synchronized void register(CropBehavior behavior) {
        Objects.requireNonNull(behavior);
        Ledger.writing(behavior.block());
        Map<Block, CropBehavior> next = new LinkedHashMap<>(registered);
        if (next.putIfAbsent(behavior.block(), behavior) != null) {
            throw new IllegalStateException(behavior.block() + " already has a crop behavior");
        }
        registered = Map.copyOf(next);
        Crop.forgetSowable();
    }

    public static Optional<CropBehavior> of(Block block) {
        return Optional.ofNullable(registered.get(block));
    }
}
