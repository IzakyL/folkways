package io.github.izakyl.folkways.plugins.person.mixin;

import io.github.izakyl.folkways.core.api.resident.Searches;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.plugins.person.walk.ResidentPathNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {

    @Redirect(
        method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                + "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/PathNavigationRegion;"),
        require = 1)
    private PathNavigationRegion folkways$seeMovingStructures(Level level, BlockPos from, BlockPos to) {
        if (!((Object) this instanceof ResidentPathNavigation) || !(level instanceof ServerLevel serverLevel)) {
            return new PathNavigationRegion(level, from, to);
        }
        Searches.starting(serverLevel);
        return Structures.region(serverLevel, from, to);
    }
}
