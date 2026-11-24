package io.github.izakyl.folkways.core.shell.cbc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.Contraption;
import io.github.izakyl.folkways.core.combat.api.ColonyDamage;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/** A drop mortar launches its round a few ticks after the drop; the colony owning the mount owns the round. */
@Mixin(MountedBigCannonContraption.class)
public abstract class DropMortarShotMixin {

    @WrapOperation(method = "actuallyFireDropMortar", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean folkways$attribute(ServerLevel level, Entity entity, Operation<Boolean> original) {
        if (entity instanceof AbstractCannonProjectile
                && ((Contraption) (Object) this).entity instanceof PitchOrientedContraptionEntity cannon
                && cannon.getController() instanceof CannonMountBlockEntity mount) {
            Facilities.colonyAt(level, WorldSpaces.at(level, mount.getBlockPos()))
                .ifPresent(id -> ColonyDamage.mark(entity, id));
        }
        return original.call(level, entity);
    }
}
