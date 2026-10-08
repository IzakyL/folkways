package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.item.HeldBook;
import io.github.izakyl.folkways.front.engine.net.CreatePathPacket;
import io.github.izakyl.folkways.front.api.ui.PathOffers;
import io.github.izakyl.folkways.front.ui.screen.Drawing;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class PathSelection {

    private static final int MAX_POINTS = CreatePathPacket.MAX_POINTS;

    private static final float[] SET = {0.95F, 0.95F, 0.95F};

    /** The same amber as a commissioned work's placard, so the draft reads as the footprint it will leave. */
    private static final float[] DRAFT = ColonyHighlightState.PLACARD_RGB;

    private static final float NEXT_FILL = 0.18F;

    private static final float NEXT_LINE = 0.55F;

    private static final List<io.github.izakyl.folkways.front.engine.colony.ColonyPath> PATHS = new ArrayList<>();

    public static void overview(List<net.minecraft.nbt.CompoundTag> paths) {
        PATHS.clear();
        paths.forEach(tag -> io.github.izakyl.folkways.front.engine.colony.ColonyPath.load(
            io.github.izakyl.folkways.core.api.persist.Reader.of(tag)).ifPresent(PATHS::add));
    }

    private static final List<BlockPos> POINTS = new ArrayList<>();

    static {
        Drawing.install(Shape.Gesture.LINE, new Draft<Drawn>(PathSelection::drawn, PathSelection::clear,
            path -> Component.translatable("folkways.zoneconfig.drawn", path.points().size()),
            (colony, line, delegation, settings) -> new CreatePathPacket(colony, line.points(), delegation, settings),
            id -> PathOffers.of(id).<Consumer<Drawn>>map(offer -> line -> offer.take().take(line.points()))));
        Drawing.offers(Shape.Gesture.LINE, () -> PathOffers.all().stream()
            .map(offer -> new Drawing.Offered(offer.id(), offer.nameKey(), offer.icon()))
            .toList());
    }

    private PathSelection() {
    }

    public record Drawn(List<BlockPos> points) {

        public Drawn {
            points = List.copyOf(points);
        }
    }

    public static Optional<Drawn> drawn() {
        return POINTS.size() < 2 ? Optional.empty() : Optional.of(new Drawn(POINTS));
    }

    public static void clear() {
        POINTS.clear();
    }

    public static boolean drafting() {
        return !POINTS.isEmpty();
    }

    public static void tick() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            clear();
            return;
        }
        Optional<HeldBook> book = HeldBook.bound(player);
        if (book.isEmpty() || book.get().gesture() != Shape.Gesture.LINE) {
            clear();
        }
    }

    public static boolean handleLeftClick() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return false;
        }
        if (player.isShiftKeyDown()) {
            if (!POINTS.isEmpty()) {
                clear();
                Draft.say(Component.translatable("folkways.path.dropped"));
            }
            return true;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return true;
        }
        if (POINTS.size() >= MAX_POINTS) {
            Draft.say(Component.translatable("folkways.path.full", MAX_POINTS));
            return true;
        }
        POINTS.add(hit.getBlockPos().immutable());
        Draft.say(Component.translatable("folkways.path.point", POINTS.size()));
        return true;
    }

    static Optional<java.util.UUID> aimedPath(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }
        Vec3 target = hit.getLocation();
        for (var path : PATHS) {
            for (int at = 1; at < path.points().size(); at++) {
                Vec3 a = Vec3.atCenterOf(path.points().get(at - 1));
                Vec3 b = Vec3.atCenterOf(path.points().get(at));
                Vec3 delta = b.subtract(a);
                double t = delta.lengthSqr() == 0 ? 0 : Mth.clamp(
                    target.subtract(a).dot(delta) / delta.lengthSqr(), 0, 1);
                if (a.add(delta.scale(t)).distanceToSqr(target) < 1.5) {
                    return Optional.of(path.id());
                }
            }
        }
        return Optional.empty();
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || HeldBook.bound(minecraft.player).isEmpty()) {
            return;
        }
        Level level = minecraft.level;
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        // The draft is laid out as the road it would become: an amber ribbon on the ground through the points set
        // down so far, running on, fainter, to the block the player is aiming at.
        Optional<BlockPos> next = POINTS.isEmpty() || POINTS.size() >= MAX_POINTS ? Optional.empty() : aimed(minecraft);
        Set<BlockPos> laid = ColonyHighlightState.ribbon(POINTS);
        VertexConsumer fills = buffers.getBuffer(RenderType.debugFilledBox());
        for (BlockPos cell : laid) {
            ColonyHighlightState.ribbonCell(level, poseStack, fills, cell, DRAFT, ColonyHighlightState.RIBBON_FILL);
        }
        next.ifPresent(aim -> {
            for (BlockPos cell : ColonyHighlightState.ribbon(List.of(POINTS.getLast(), aim))) {
                if (!laid.contains(cell)) {
                    ColonyHighlightState.ribbonCell(level, poseStack, fills, cell, DRAFT, NEXT_FILL);
                }
            }
        });
        buffers.endBatch(RenderType.debugFilledBox());

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (var path : PATHS) {
            for (int at = 1; at < path.points().size(); at++) {
                Vec3 a = ColonyHighlightState.surface(level, path.points().get(at - 1));
                Vec3 b = ColonyHighlightState.surface(level, path.points().get(at));
                if (a.distanceToSqr(camera) < 4096 || b.distanceToSqr(camera) < 4096)
                    segment(poseStack, lines, a, b, SET, 1.0F);
            }
        }
        // Each point set down stands as a short post on the ribbon, so it reads apart from the cursor's own outline.
        for (BlockPos point : POINTS) {
            Vec3 foot = ColonyHighlightState.surface(level, point);
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(foot.x - 0.3D, foot.y, foot.z - 0.3D,
                foot.x + 0.3D, foot.y + 0.6D, foot.z + 0.3D), DRAFT[0], DRAFT[1], DRAFT[2], 1.0F);
        }
        for (int at = 1; at < POINTS.size(); at++) {
            segment(poseStack, lines, ColonyHighlightState.surface(level, POINTS.get(at - 1)),
                ColonyHighlightState.surface(level, POINTS.get(at)), DRAFT, 1.0F);
        }
        next.ifPresent(aim -> segment(poseStack, lines, ColonyHighlightState.surface(level, POINTS.getLast()),
            ColonyHighlightState.surface(level, aim), DRAFT, NEXT_LINE));
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static Optional<BlockPos> aimed(Minecraft minecraft) {
        return minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            ? Optional.of(hit.getBlockPos().immutable())
            : Optional.empty();
    }

    static List<io.github.izakyl.folkways.front.engine.colony.ColonyPath> paths() {
        return List.copyOf(PATHS);
    }

    static void segment(PoseStack poseStack, VertexConsumer lines, Vec3 from, Vec3 to, float[] rgb, float alpha) {
        float dx = (float) (to.x - from.x);
        float dy = (float) (to.y - from.y);
        float dz = (float) (to.z - from.z);
        float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0.0F) {
            return;
        }
        PoseStack.Pose pose = poseStack.last();
        lines.addVertex(pose, (float) from.x, (float) from.y, (float) from.z)
            .setColor(rgb[0], rgb[1], rgb[2], alpha)
            .setNormal(pose, dx / length, dy / length, dz / length);
        lines.addVertex(pose, (float) to.x, (float) to.y, (float) to.z)
            .setColor(rgb[0], rgb[1], rgb[2], alpha)
            .setNormal(pose, dx / length, dy / length, dz / length);
    }
}
