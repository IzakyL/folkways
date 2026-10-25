package io.github.izakyl.folkways.create.elevator;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorContactBlock;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import io.github.izakyl.folkways.front.client.ShadowPass;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Shows the assembled cabin and the space it sweeps between reachable floors while holding the book. */
@EventBusSubscriber(modid = FolkwaysCreateMod.ID, value = Dist.CLIENT)
public final class ElevatorOutlines {
    /** Uninflated bounds actually submitted by the last rendered frame, for read-only client inspection. */
    public record Outline(int entityId, AABB cabin, Optional<AABB> sweep) { }
    private static List<Outline> shown = List.of();

    public static List<Outline> shown() {
        return shown;
    }

    private record Floors(long refreshed, int bottom, int top) { }
    private static final Map<AbstractContraptionEntity, Floors> FLOORS = new HashMap<>();

    private ElevatorOutlines() { }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ShadowPass.active()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || ColonyBookItem.heldBook(minecraft.player).isEmpty()) {
            shown = List.of();
            FLOORS.clear();
            return;
        }
        FLOORS.keySet().removeIf(vehicle -> vehicle.level() != minecraft.level || !vehicle.isAlive());
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var poses = event.getPoseStack();
        var buffers = minecraft.renderBuffers().bufferSource();
        List<Outline> drawn = new ArrayList<>();
        poses.pushPose();
        poses.translate(-camera.x, -camera.y, -camera.z);
        for (var entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractContraptionEntity vehicle)
                    || !(vehicle.getContraption() instanceof ElevatorContraption lift)
                    || lift.bounds == null || vehicle.distanceToSqr(camera) > 128 * 128) continue;
            // Elevators translate vertically; transforming both corners also interpolates a moving cabin.
            Vec3 low = vehicle.toGlobalVector(new Vec3(lift.bounds.minX, lift.bounds.minY, lift.bounds.minZ), partial);
            Vec3 high = vehicle.toGlobalVector(new Vec3(lift.bounds.maxX, lift.bounds.maxY, lift.bounds.maxZ), partial);
            AABB cabin = new AABB(low, high);
            Floors floors = FLOORS.compute(vehicle, (key, previous) -> previous == null
                || minecraft.level.getGameTime() - previous.refreshed() >= 20 ? floors(vehicle, lift) : previous);
            Optional<AABB> swept = Optional.empty();
            if (floors.bottom() <= floors.top()) {
                AABB sweep = new AABB(cabin.minX, floors.bottom() + lift.bounds.minY - lift.getContactYOffset(),
                    cabin.minZ, cabin.maxX, floors.top() + lift.bounds.maxY - lift.getContactYOffset(), cabin.maxZ);
                swept = Optional.of(sweep);
                LevelRenderer.renderLineBox(poses, buffers.getBuffer(RenderType.lines()), sweep.inflate(0.025),
                    1.0F, 0.65F, 0.2F, 0.8F);
            }
            drawn.add(new Outline(vehicle.getId(), cabin, swept));
            LevelRenderer.renderLineBox(poses, buffers.getBuffer(RenderType.lines()), cabin.inflate(0.04),
                0.25F, 0.85F, 1.0F, 1.0F);
        }
        poses.popPose();
        buffers.endBatch(RenderType.lines());
        shown = List.copyOf(drawn);
    }

    private static Floors floors(AbstractContraptionEntity vehicle, ElevatorContraption lift) {
        int bottom = Integer.MAX_VALUE;
        int top = Integer.MIN_VALUE;
        // Create supplies its floor list to tracking clients. Newly tracked contraptions may not have it yet;
        // in that case read loaded contacts in this column without requiring an interaction with its controls.
        for (var floor : lift.namesList) {
            int y = floor.getFirst();
            if (!lift.isTargetUnreachable(y)) {
                bottom = Math.min(bottom, y);
                top = Math.max(top, y);
            }
        }
        if (bottom > top) {
            var level = vehicle.level();
            var column = lift.getGlobalColumn();
            for (int y = Math.max(level.getMinBuildHeight(), lift.minContactY);
                    y <= Math.min(level.getMaxBuildHeight() - 1, lift.maxContactY); y++) {
                BlockPos at = new BlockPos(column.x(), y, column.z());
                if (level.hasChunkAt(at) && level.getBlockState(at).getBlock() instanceof ElevatorContactBlock
                        && column.equals(ElevatorContactBlock.getColumnCoords(level, at))) {
                    bottom = Math.min(bottom, y);
                    top = Math.max(top, y);
                }
            }
        }
        return new Floors(vehicle.level().getGameTime(), bottom, top);
    }
}
