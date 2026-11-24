package io.github.izakyl.folkways.core.shell.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockEntity.class)
public interface BlockEntityAccessor extends io.github.izakyl.folkways.core.world.api.BlockEntities.Data {

    @Accessor("customPersistentData")
    CompoundTag folkways$persistentData();
}
