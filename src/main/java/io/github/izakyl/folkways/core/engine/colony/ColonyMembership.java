package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.resident.body.FolkwaysAttachments;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ColonyMembership {
    private ColonyMembership() {
    }

    public static Optional<UUID> owner(BlockEntity blockEntity) {
        Optional<UUID> stored = blockEntity.getExistingDataOrNull(FolkwaysAttachments.COLONY_MEMBER);
        return stored == null ? Optional.empty() : stored;
    }

    public static void join(BlockEntity blockEntity, UUID colonyId) {
        blockEntity.setData(FolkwaysAttachments.COLONY_MEMBER, Optional.of(colonyId));
    }

    public static void leave(BlockEntity blockEntity) {
        blockEntity.removeData(FolkwaysAttachments.COLONY_MEMBER);
    }
}
