package io.github.izakyl.folkways.firearms.warfare.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.Fh77bwEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ArtilleryEntity.class, remap = false)
public abstract class ArtilleryAimMixin {

    // A resident's client head pose is not a turret command. Follow the native synced server aim instead.
    @Redirect(method = "baseTick", at = @At(value = "INVOKE",
        target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/ArtilleryEntity;"
            + "getNthEntity(I)Lnet/minecraft/world/entity/Entity;"))
    private Entity folkways$authoritativeResidentAim(ArtilleryEntity artillery, int seat) {
        Entity controller = artillery.getNthEntity(seat);
        if (artillery.level().isClientSide() && artillery instanceof Fh77bwEntity
            && controller instanceof ResidentEntity) {
            return null;
        }
        return controller;
    }
}
