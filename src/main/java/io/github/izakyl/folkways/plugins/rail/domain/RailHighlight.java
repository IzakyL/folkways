package io.github.izakyl.folkways.plugins.rail.domain;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import io.github.izakyl.folkways.core.api.colony.Books;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

public final class RailHighlight {

    private static final int REQUEST_INTERVAL_TICKS = 20;
    private static final double RANGE = 96.0D;
    private static final float RED = 0.95F;
    private static final float GREEN = 0.75F;
    private static final float BLUE = 0.25F;

    private static UUID activeColonyId;
    private static int requestCooldown;

    private RailHighlight() {
    }

    public static void install() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, RailHighlight::onClientTick);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, RailHighlight::onRenderLevel);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            reset();
            return;
        }
        Optional<UUID> colony = Books.heldColony(minecraft.player);
        if (colony.isEmpty()) {
            reset();
            return;
        }
        if (!colony.get().equals(activeColonyId)) {
            activeColonyId = colony.get();
            RailPackets.forget();
            requestCooldown = 0;
        }
        if (requestCooldown <= 0 && minecraft.getConnection() != null) {
            requestCooldown = REQUEST_INTERVAL_TICKS;
            PacketDistributor.sendToServer(new RequestRosterPacket(colony.get()));
        } else {
            requestCooldown--;
        }
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        List<UUID> recognized = RailPackets.recognized();
        Minecraft minecraft = Minecraft.getInstance();
        if (recognized.isEmpty() || minecraft.level == null || minecraft.player == null
            || Books.heldColony(minecraft.player).isEmpty()) {
            return;
        }

        GlobalRailwayManager railways = Create.RAILWAYS.sided(minecraft.level);
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (UUID id : recognized) {
            Train train = railways.trains.get(id);
            if (train == null) {
                continue;
            }
            for (Carriage carriage : train.carriages) {
                frameDriverSeats(carriage, camera, partialTick, poseStack, lines);
            }
        }
        buffers.endBatch(RenderType.lines());
        poseStack.popPose();
        buffers.endBatch();
    }

    private static void frameDriverSeats(Carriage carriage, Vec3 camera, float partialTick,
            PoseStack poseStack, VertexConsumer lines) {
        CarriageContraptionEntity entity = carriage.anyAvailableEntity();
        if (entity == null || !(entity.getContraption() instanceof CarriageContraption contraption)) {
            return;
        }
        for (BlockPos local : contraption.conductorSeats.keySet()) {
            Vec3 seat = entity.toGlobalVector(VecHelper.getCenterOf(local), partialTick);
            if (seat.distanceToSqr(camera) > RANGE * RANGE) {
                continue;
            }
            LevelRenderer.renderLineBox(poseStack, lines, AABB.ofSize(seat, 1.0D, 1.0D, 1.0D).inflate(0.02D),
                RED, GREEN, BLUE, 1.0F);
        }
    }

    private static void reset() {
        activeColonyId = null;
        requestCooldown = 0;
        RailPackets.forget();
    }
}
