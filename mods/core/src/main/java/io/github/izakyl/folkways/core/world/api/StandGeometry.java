package io.github.izakyl.folkways.core.world.api;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class StandGeometry {

    private StandGeometry() {
    }

    public static Set<Stand> footingsOf(ServerLevel level, WorldPos at) {
        Set<Stand> cells = new LinkedHashSet<>();
        BlockPos storage = WorldSpaces.storage(level, at).orElse(null);
        if (storage == null) {
            return Set.of();
        }
        for (BlockPos feet : Reach.workableCells(level, storage)) {
            WorldPos footing = WorldPos.of(level, feet);
            if (footing.sameRealm(at)) {
                cells.add(new Stand(footing));
            }
        }
        return cells;
    }

}
