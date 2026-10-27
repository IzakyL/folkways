package io.github.izakyl.folkways.raid.mixin;

import io.github.izakyl.folkways.raid.RaidEdits;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Projectile.class)
abstract class ProjectileMixin {

    @Redirect(
        method = "onHitBlock",
        at =
        @At(
        value = "INVOKE",
        target =
        "Lnet/minecraft/world/level/block/state/BlockState;onProjectileHit(Lnet/minec"
        + "raft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lne"
        + "t/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/entity/projectile"
        + "/Projectile;)V"))
    private void folkways$blockReaction(
        BlockState receiver, Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        RaidEdits.projectileHit(state, level, hit, projectile);
    }
}
