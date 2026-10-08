package io.github.izakyl.folkways.plugins.person;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class CastLineRenderer {

    private static final ResourceLocation FLOAT_TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/entity/fishing_hook.png");
    private static final RenderType FLOAT_TYPE = RenderType.entityCutout(FLOAT_TEXTURE);

    private static final double FLOAT_DEPTH = 0.15D;
    private static final double BOB_AMPLITUDE = 0.03D;
    private static final double BOB_PERIOD = 40.0D;

    private static final int SEGMENTS = 16;

    private CastLineRenderer() {
    }

    static void render(ResidentEntity resident, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light, EntityRenderDispatcher dispatcher) {
        BlockPos cell = resident.castLine().orElse(null);
        if (cell == null) {
            return;
        }
        Vec3 body = resident.getPosition(partialTick);
        Vec3 water = floatPosition(resident, cell, partialTick);
        Vec3 hand = handPosition(resident, partialTick);

        pose.pushPose();
        pose.translate(water.x - body.x, water.y - body.y, water.z - body.z);

        pose.pushPose();
        pose.scale(0.5F, 0.5F, 0.5F);
        pose.mulPose(dispatcher.cameraOrientation());
        PoseStack.Pose quad = pose.last();
        VertexConsumer floats = buffers.getBuffer(FLOAT_TYPE);
        corner(floats, quad, light, 0.0F, 0, 0, 1);
        corner(floats, quad, light, 1.0F, 0, 1, 1);
        corner(floats, quad, light, 1.0F, 1, 1, 0);
        corner(floats, quad, light, 0.0F, 1, 0, 0);
        pose.popPose();

        float dx = (float) (hand.x - water.x);
        float dy = (float) (hand.y - water.y);
        float dz = (float) (hand.z - water.z);
        VertexConsumer line = buffers.getBuffer(RenderType.lineStrip());
        PoseStack.Pose at = pose.last();
        for (int i = 0; i <= SEGMENTS; i++) {
            strand(dx, dy, dz, line, at, (float) i / SEGMENTS, (float) (i + 1) / SEGMENTS);
        }
        pose.popPose();
    }

    private static Vec3 floatPosition(ResidentEntity resident, BlockPos cell, float partialTick) {
        double phase = (resident.level().getGameTime() + partialTick) / BOB_PERIOD * Math.PI * 2.0D;
        return new Vec3(
            cell.getX() + 0.5D,
            cell.getY() + 1.0D - FLOAT_DEPTH + Math.sin(phase) * BOB_AMPLITUDE,
            cell.getZ() + 0.5D);
    }

    private static Vec3 handPosition(ResidentEntity resident, float partialTick) {
        int side = resident.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        float yaw = Mth.lerp(partialTick, resident.yBodyRotO, resident.yBodyRot) * ((float) Math.PI / 180F);
        double sin = Mth.sin(yaw);
        double cos = Mth.cos(yaw);
        float scale = resident.getScale();
        double out = side * 0.35D * scale;
        double forward = 0.8D * scale;
        float crouch = resident.isCrouching() ? -0.1875F : 0.0F;
        return resident.getEyePosition(partialTick)
            .add(-cos * out - sin * forward, crouch - 0.45D * scale, -sin * out + cos * forward);
    }

    private static void corner(VertexConsumer buffer, PoseStack.Pose pose, int light, float x, int y,
            int u, int v) {
        buffer.addVertex(pose, x - 0.5F, y - 0.5F, 0.0F)
            .setColor(-1)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    private static void strand(float dx, float dy, float dz, VertexConsumer buffer, PoseStack.Pose pose,
            float from, float to) {
        float x = dx * from;
        float y = dy * (from * from + from) * 0.5F + 0.25F;
        float z = dz * from;
        float nx = dx * to - x;
        float ny = dy * (to * to + to) * 0.5F + 0.25F - y;
        float nz = dz * to - z;
        float length = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        buffer.addVertex(pose, x, y, z)
            .setColor(-16777216)
            .setNormal(pose, nx / length, ny / length, nz / length);
    }
}
