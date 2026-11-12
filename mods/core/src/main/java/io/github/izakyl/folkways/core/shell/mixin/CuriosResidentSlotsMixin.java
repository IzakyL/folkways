package io.github.izakyl.folkways.core.shell.mixin;

import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.theillusivec4.curios.api.type.ISlotType;
import top.theillusivec4.curios.common.data.CuriosEntityManager;

/** Copies the effective player assignment at the framework's own lookup, including reloads and client sync. */
@Mixin(value = CuriosEntityManager.class, remap = false)
abstract class CuriosResidentSlotsMixin {
    @Inject(method = "getEntitySlots", at = @At("HEAD"), cancellable = true, require = 1)
    private void folkways$playerSlots(EntityType<?> type, CallbackInfoReturnable<Map<String, ISlotType>> callback) {
        if (type != EntityType.PLAYER && ResidentKinds.of(BuiltInRegistries.ENTITY_TYPE.getKey(type)).isPresent()) {
            callback.setReturnValue(((CuriosEntityManager) (Object) this).getEntitySlots(EntityType.PLAYER));
        }
    }
}
