package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Where a block's corner stands in the world, and how its axes turn there, when its frame may be moving. */
record Placed(Vec3 origin, Matrix3f turn) {

    static Placed at(Level level, BlockPos anchor, float partialTick) {
        return WorldSpaces.containing(level, anchor).map(frame -> {
            Vec3 local = Vec3.atLowerCornerOf(frame.local(anchor));
            Vec3 origin = frame.toWorld(local, partialTick);
            Vec3 x = frame.toWorld(local.add(1.0D, 0.0D, 0.0D), partialTick).subtract(origin);
            Vec3 y = frame.toWorld(local.add(0.0D, 1.0D, 0.0D), partialTick).subtract(origin);
            Vec3 z = frame.toWorld(local.add(0.0D, 0.0D, 1.0D), partialTick).subtract(origin);
            return new Placed(origin, new Matrix3f(
                (float) x.x, (float) x.y, (float) x.z,
                (float) y.x, (float) y.y, (float) y.z,
                (float) z.x, (float) z.y, (float) z.z));
        }).orElseGet(() -> new Placed(Vec3.atLowerCornerOf(anchor), new Matrix3f()));
    }

    Vec3 point(Vec3 offset) {
        Vector3f turned = turn.transform(new Vector3f((float) offset.x, (float) offset.y, (float) offset.z));
        return origin.add(turned.x, turned.y, turned.z);
    }

    Vec3 centre() {
        return point(new Vec3(0.5D, 0.5D, 0.5D));
    }

    void enter(PoseStack poseStack) {
        poseStack.pushPose();
        poseStack.translate(origin.x, origin.y, origin.z);
        poseStack.last().pose().mul(new Matrix4f(turn));
        poseStack.last().normal().mul(turn);
    }
}
