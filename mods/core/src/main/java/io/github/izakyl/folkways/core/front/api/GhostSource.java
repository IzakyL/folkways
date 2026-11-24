package io.github.izakyl.folkways.core.front.api;

import io.github.izakyl.folkways.core.colony.api.ColonyView;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

public interface GhostSource {

    default List<Placard> placards(ColonyView colony) {
        return List.of();
    }

    default List<Ghost> payloadGhosts(ColonyView colony, Set<UUID> orders, int most, BlockPos near) {
        return List.of();
    }

    default List<CompoundTag> previewRecipes(ColonyView colony) {
        return List.of();
    }

    default List<Ghost> ghosts(ColonyView colony) {
        return List.of();
    }

    default List<Ghost> ghosts(ColonyView colony, int most, BlockPos near) {
        return ghosts(colony).stream().sorted(Comparator.comparingDouble(ghost -> ghost.pos().distSqr(near)))
            .limit(most).toList();
    }
}
