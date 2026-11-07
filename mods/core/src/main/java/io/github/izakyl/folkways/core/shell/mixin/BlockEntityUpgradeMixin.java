package io.github.izakyl.folkways.core.shell.mixin;

import io.github.izakyl.folkways.base.persist.upgrade.RawUpgrades;
import io.github.izakyl.folkways.base.persist.upgrade.Surface;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntity.class)
abstract class BlockEntityUpgradeMixin {
    @Inject(method = {"loadWithComponents", "loadCustomOnly"}, at = @At("HEAD"))
    private void folkways$upgrade(CompoundTag raw, HolderLookup.Provider registries, CallbackInfo callback) {
        RawUpgrades.load(Surface.BLOCK_ENTITY, raw);
    }

    @Inject(method = {"saveWithoutMetadata", "saveCustomOnly"}, at = @At("RETURN"))
    private void folkways$stamp(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> callback) {
        BlockEntity self = (BlockEntity) (Object) this;
        RawUpgrades.stamp(callback.getReturnValue(),
            RawUpgrades.ownId(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(self.getType()).toString()));
    }
}
