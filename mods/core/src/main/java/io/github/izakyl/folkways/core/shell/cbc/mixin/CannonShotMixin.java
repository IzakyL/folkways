package io.github.izakyl.folkways.core.shell.cbc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.izakyl.folkways.core.combat.api.ColonyDamage;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.shell.cbc.CbcTrigger;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

@Mixin({MountedBigCannonContraption.class, MountedAutocannonContraption.class})
public abstract class CannonShotMixin {

    @WrapOperation(method = "fireShot", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean folkways$attribute(ServerLevel level, Entity entity, Operation<Boolean> original,
                                       ServerLevel world, PitchOrientedContraptionEntity cannon) {
        if (entity instanceof AbstractCannonProjectile && cannon.getController() instanceof CannonMountBlockEntity mount) {
            var colony = Facilities.colonyAt(level, WorldSpaces.at(level, mount.getBlockPos()))
                .or(() -> ColonyDamage.party(CbcTrigger.operator()));
            colony.ifPresent(id -> ColonyDamage.mark(entity, id));
        }
        return original.call(level, entity);
    }
}
