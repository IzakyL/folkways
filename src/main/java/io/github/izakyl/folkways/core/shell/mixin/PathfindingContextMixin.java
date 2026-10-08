package io.github.izakyl.folkways.core.shell.mixin;

import io.github.izakyl.folkways.core.api.terms.ObstructedPathRegion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PathfindingContext.class)
public abstract class PathfindingContextMixin {

    @Redirect(
        method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/entity/Mob;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getPathTypeCache()"
                + "Lnet/minecraft/world/level/pathfinder/PathTypeCache;"),
        require = 1)
    private PathTypeCache folkways$noGlobalCacheOverAStructure(ServerLevel serverLevel,
            CollisionGetter snapshot, Mob mob) {
        return snapshot instanceof ObstructedPathRegion ? null : serverLevel.getPathTypeCache();
    }
}
