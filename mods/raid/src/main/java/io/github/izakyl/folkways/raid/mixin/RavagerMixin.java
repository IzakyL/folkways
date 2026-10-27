package io.github.izakyl.folkways.raid.mixin;

import io.github.izakyl.folkways.raid.RaidEdits;
import io.github.izakyl.folkways.raid.RaidMembers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Ravager.class)
abstract class RavagerMixin {

    @Redirect(
        method = "aiStep",
        at =
        @At(
        value = "INVOKE",
        target =
        "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z"
        + "Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean folkways$breakLeaves(Level level, BlockPos at, boolean drops, Entity entity) {
        Mob mob = (Mob) (Object) this;
        return RaidMembers.marked(mob) ? RaidEdits.breakBlock(mob, at) : level.destroyBlock(at, drops, entity);
    }
}
