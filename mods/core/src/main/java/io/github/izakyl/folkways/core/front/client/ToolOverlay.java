package io.github.izakyl.folkways.core.front.client;

import io.github.izakyl.folkways.core.front.api.ui.ModeOverlays;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

final class ToolOverlay {
    private ToolOverlay() {
    }

    static Optional<OverheadTools.Mark> aimed() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
            || ColonyHighlightState.heldColonyId(minecraft.player) == null) {
            return Optional.empty();
        }
        return OverheadTools.active().stream()
            .flatMap(tool -> tool.marks().stream())
            .filter(mark -> {
                Aim aim = Aim.of(minecraft.player).in(minecraft.level, mark.min());
                AABB box = AABB.encapsulatingFullBlocks(mark.min(), mark.max()).inflate(0.04D);
                return box.contains(aim.from()) || box.clip(aim.from(), aim.at(Aim.range())).isPresent();
            })
            // A cell or worker inside an area takes precedence over the area's enclosing box.
            .min(Comparator.<OverheadTools.Mark>comparingDouble(
                mark -> AABB.encapsulatingFullBlocks(mark.min(), mark.max()).getSize())
                .thenComparingDouble(mark -> {
                    Aim aim = Aim.of(minecraft.player).in(minecraft.level, mark.min());
                    return AABB.encapsulatingFullBlocks(mark.min(), mark.max())
                        .inflate(0.04D)
                        .clip(aim.from(), aim.at(Aim.range()))
                        .map(aim.from()::distanceToSqr)
                        .orElse(0.0D);
                }));
    }

    static void render(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
            || minecraft.level == null || minecraft.player == null
            || ColonyHighlightState.heldColonyId(minecraft.player) == null) {
            return;
        }
        var pose = event.getPoseStack();
        var camera = event.getCamera().getPosition();
        var buffers = minecraft.renderBuffers().bufferSource();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        OverheadTools.all().forEach(tool -> {
            for (OverheadTools.Mark mark : tool.marks()) {
                Placed.drawAt(minecraft.level, pose, mark.min(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false), () -> {
                        int rgb = mark.color();
                        LevelRenderer.renderLineBox(pose,
                            ModeOverlays.vertices(buffers.getBuffer(RenderType.lines()), tool.mode()),
                            AABB.encapsulatingFullBlocks(mark.min(), mark.max()).inflate(0.035D),
                            ((rgb >> 16) & 255) / 255.0F,
                            ((rgb >> 8) & 255) / 255.0F,
                            (rgb & 255) / 255.0F, 1.0F);
                    });
            }
        });
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
