package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.izakyl.folkways.front.api.Ghost;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * What an offer drew over the shape in hand, shown standing in the world while its window is closed to look at it.
 * It belongs to that one shape: once the shape is moved, redrawn or let go, it goes too.
 */
final class ShapePreview {

    private static final GhostRenderer GHOSTS = new GhostRenderer();

    private static ResourceLocation offer;
    private static Object shape;
    private static Supplier<? extends Optional<?>> drawn = Optional::empty;
    private static List<Ghost> ghosts = List.of();

    private ShapePreview() {
    }

    static void show(ResourceLocation by, Object over, Supplier<? extends Optional<?>> current, List<Ghost> shown) {
        clear();
        offer = by;
        shape = over;
        drawn = current;
        ghosts = List.copyOf(shown);
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            GHOSTS.show(level, ghosts);
        }
    }

    /** Whether the preview standing now was made by this offer over this shape. */
    static boolean of(ResourceLocation by, Object over) {
        return offer != null && offer.equals(by) && Objects.equals(shape, over);
    }

    /** The offer whose preview stands now, if any. */
    static Optional<ResourceLocation> offer() {
        return Optional.ofNullable(offer);
    }

    static boolean showing() {
        return offer != null;
    }

    static void clear() {
        offer = null;
        shape = null;
        drawn = Optional::empty;
        ghosts = List.of();
        GHOSTS.clear();
    }

    static void tick() {
        if (offer == null) {
            return;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null || !drawn.get().equals(Optional.of(shape))) {
            clear();
            return;
        }
        // What stands in the ghosts' cells may change while they are looked at, and the marks follow it.
        GHOSTS.show(level, ghosts);
    }

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || GHOSTS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        GHOSTS.drawModels(level, camera, partialTick);
        GHOSTS.drawFills(level, poseStack, buffers.getBuffer(RenderType.debugFilledBox()), partialTick);
        buffers.endBatch(RenderType.debugFilledBox());
        GHOSTS.drawOutlines(level, poseStack, buffers.getBuffer(RenderType.lines()), partialTick);
        buffers.endBatch(RenderType.lines());

        poseStack.popPose();
    }
}
