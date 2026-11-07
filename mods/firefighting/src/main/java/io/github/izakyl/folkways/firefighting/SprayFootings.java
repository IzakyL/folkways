package io.github.izakyl.folkways.firefighting;

import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Reach;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class SprayFootings {
    private static final double SAFETY = 0.05D;

    private SprayFootings() {
    }

    static boolean serves(ServerLevel level, Mob body, BlockPos feet, BlockPos fire, double range, boolean hose) {
        if (!level.isLoaded(fire) || !Footing.withFeetAt(level, feet)
                || level.getBlockState(feet).is(Blocks.FIRE) || level.getBlockState(feet).is(Blocks.SOUL_FIRE)) {
            return false;
        }
        Vec3 eye = Vec3.atBottomCenterOf(feet).add(0, body.getEyeHeight(), 0);
        Vec3 target = Vec3.atCenterOf(fire);
        if (!within(eye, target, range)) {
            return false;
        }
        AABB eyes = new AABB(eye, eye).inflate(Reach.IN_THE_CELL, Reach.CELL_HEIGHT, Reach.IN_THE_CELL);
        if (hose && nearest(eyes, target) < NativeSprayBridge.HOSE_FORWARD * NativeSprayBridge.HOSE_FORWARD) {
            return false;
        }
        // This box contains every eye-to-target segment, including positions between any sampled rays.
        // The hose starts along that segment and slightly below it; include its whole vertical offset too.
        AABB sight = eyes.minmax(new AABB(target, target));
        if (hose) {
            sight = sight.expandTowards(0, -NativeSprayBridge.HOSE_DROP, 0);
        }
        for (int x = (int) Math.floor(sight.minX) >> 4; x <= (int) Math.floor(sight.maxX) >> 4; x++) {
            for (int z = (int) Math.floor(sight.minZ) >> 4; z <= (int) Math.floor(sight.maxZ) >> 4; z++) {
                if (!level.isLoaded(new BlockPos(x << 4, feet.getY(), z << 4))) {
                    return false;
                }
            }
        }
        return !level.getBlockCollisions(null, sight).iterator().hasNext();
    }

    static boolean within(Vec3 center, Vec3 target, double range) {
        double limit = range - Reach.IN_THE_CELL - SAFETY;
        double vertical = Math.abs(center.y - target.y) + Reach.CELL_HEIGHT;
        double dx = center.x - target.x;
        double dz = center.z - target.z;
        return limit > 0 && dx * dx + vertical * vertical + dz * dz <= limit * limit;
    }

    private static double nearest(AABB box, Vec3 point) {
        double dx = Math.max(Math.max(box.minX - point.x, 0), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0), point.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }
}
