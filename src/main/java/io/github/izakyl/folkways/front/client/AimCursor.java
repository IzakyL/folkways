package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.item.HeldBook;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Outlines the cell the book is aimed at where the game does not: past the hand's reach, or in open air. */
final class AimCursor {
    private static final float SHADE = 0.95F;
    private static final float ALPHA = 0.6F;

    private AimCursor() {
    }

    static void render(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
            || minecraft.player == null || Overhead.screened(minecraft)) {
            return;
        }
        Optional<BlockPos> cell = HeldBook.bound(minecraft.player).flatMap(book ->
            book.gesture() == Shape.Gesture.BOX ? ZoneSelection.cursor(minecraft.player) : Aim.cell(minecraft.player));
        if (cell.isEmpty() || (!Overhead.on() && minecraft.hitResult instanceof BlockHitResult outlined
            && outlined.getType() == HitResult.Type.BLOCK && outlined.getBlockPos().equals(cell.get()))) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        LevelRenderer.renderLineBox(poseStack, buffers.getBuffer(RenderType.lines()),
            new AABB(cell.get()).inflate(0.01D), SHADE, SHADE, SHADE, ALPHA);
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
