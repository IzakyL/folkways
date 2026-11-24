package io.github.izakyl.folkways.core.world.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class BlockEntities {

    public interface Data {
        CompoundTag folkways$persistentData();
    }

    private BlockEntities() {
    }

    public static CompoundTag data(BlockEntity entity) {
        CompoundTag data = ((Data) entity).folkways$persistentData();
        return data == null ? new CompoundTag() : data;
    }
}
