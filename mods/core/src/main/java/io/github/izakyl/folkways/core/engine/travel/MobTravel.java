package io.github.izakyl.folkways.core.engine.travel;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** Local travel for mobs that do not take resident work. Never loads a destination chunk. */
public final class MobTravel {
    private MobTravel() { }

    public static boolean approach(Mob mob, WorldPos destination, double speed) {
        if (!(mob.level() instanceof ServerLevel level)) { return false; }
        var resolved = WorldSpaces.resolve(level.getServer(), destination);
        if (resolved.isEmpty() || resolved.get().level() != level || !level.isPositionEntityTicking(resolved.get().cell())) { return false; }
        BlockPos cell = resolved.get().cell();
        return mob.getNavigation().moveTo(cell.getX() + .5, cell.getY(), cell.getZ() + .5, speed);
    }

    public static java.util.Optional<BlockPos> obstruction(Mob mob, WorldPos destination) {
        if (!(mob.level() instanceof ServerLevel level)) { return java.util.Optional.empty(); }
        var resolved = WorldSpaces.resolve(level.getServer(), destination);
        if (resolved.isEmpty() || resolved.get().level() != level) { return java.util.Optional.empty(); }
        var end = net.minecraft.world.phys.Vec3.atCenterOf(resolved.get().cell()).add(0, 1, 0);
        var hit = level.clip(new net.minecraft.world.level.ClipContext(mob.getEyePosition(), end,
            net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, mob));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
            && mob.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(hit.getBlockPos())) <= 9
            ? java.util.Optional.of(hit.getBlockPos().immutable()) : java.util.Optional.empty();
    }

    public static boolean reaches(Mob mob, BlockPos destination) {
        var path = mob.getNavigation().createPath(destination, 2);
        return path != null && path.canReach();
    }
}
