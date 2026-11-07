package io.github.izakyl.folkways.core.shell.mixin;

import io.github.izakyl.folkways.base.persist.upgrade.RawUpgrades;
import io.github.izakyl.folkways.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.core.shell.neoforge.FolkwaysAttachments;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class EntityUpgradeMixin {
    @Inject(method = "load", at = @At("HEAD"))
    private void folkways$upgrade(CompoundTag raw, CallbackInfo callback) {
        RawUpgrades.load(Surface.ENTITY, raw);
        FolkwaysAttachments.loading((Entity) (Object) this, raw);
    }

    @Inject(method = "saveWithoutId", at = @At("HEAD"))
    private void folkways$saveBody(CompoundTag raw, CallbackInfoReturnable<CompoundTag> callback) {
        FolkwaysAttachments.saving((Entity) (Object) this, raw);
    }

    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void folkways$stamp(CompoundTag raw, CallbackInfoReturnable<CompoundTag> callback) {
        Entity self = (Entity) (Object) this;
        RawUpgrades.stamp(callback.getReturnValue(),
            RawUpgrades.ownId(BuiltInRegistries.ENTITY_TYPE.getKey(self.getType()).toString()));
    }
}
