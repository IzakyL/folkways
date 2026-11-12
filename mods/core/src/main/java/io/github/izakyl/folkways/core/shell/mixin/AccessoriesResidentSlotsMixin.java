package io.github.izakyl.folkways.core.shell.mixin;

import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import io.wispforest.accessories.api.slot.SlotType;
import io.wispforest.accessories.data.EntitySlotLoader;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The capability and UI both see the current side's synchronized player slots, without a second saved registry. */
@Mixin(value = EntitySlotLoader.class, remap = false)
abstract class AccessoriesResidentSlotsMixin {
    @Inject(method = "getSlotTypes", at = @At("HEAD"), cancellable = true, require = 1)
    private void folkways$playerSlots(boolean client, EntityType<?> type,
                                     CallbackInfoReturnable<Map<String, SlotType>> callback) {
        if (type != EntityType.PLAYER && ResidentKinds.of(BuiltInRegistries.ENTITY_TYPE.getKey(type)).isPresent()) {
            callback.setReturnValue(((EntitySlotLoader) (Object) this).getSlotTypes(client, EntityType.PLAYER));
        }
    }
}
