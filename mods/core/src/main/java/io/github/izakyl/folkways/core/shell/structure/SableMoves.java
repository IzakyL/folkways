package io.github.izakyl.folkways.core.shell.structure;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class SableMoves {
    private SableMoves() {
    }

    public static Map<WorldPos, BlockPos> before(ServerLevel level, AssemblyTransform transform,
                                               Iterable<BlockPos> blocks) {
        Map<WorldPos, BlockPos> moved = new LinkedHashMap<>();
        for (BlockPos block : blocks) {
            moved.put(WorldPos.of(level, block), transform.apply(block).immutable());
        }
        return moved;
    }

    public static void after(ServerLevel level, AssemblyTransform transform, Map<WorldPos, BlockPos> moved) {
        Map<WorldPos, WorldPos> addresses = new LinkedHashMap<>();
        moved.forEach((from, into) -> addresses.put(from, WorldPos.of(transform.getLevel(), into)));
        for (ColonyData colony : List.copyOf(ColonyRegistry.get(level.getServer()).colonies())) {
            colony.relocate(level.getServer(), addresses);
        }
    }
}
