package io.github.izakyl.folkways.core.work.internal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

final class Gaze {
    private Gaze() {
    }

    public static void turnTo(Mob body, BlockPos cell) {
        body.getLookControl().setLookAt(Vec3.atCenterOf(cell));
    }
}
