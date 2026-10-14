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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class PathSelection {

    private static final int MAX_POINTS = CreatePathPacket.MAX_POINTS;

    private static final float[] SET = {0.95F, 0.95F, 0.95F};

    /** The same amber as a commissioned work's placard, so the draft reads as the footprint it will leave. */
    private static final float[] DRAFT = ColonyHighlightState.PLACARD_RGB;

    private static final float NEXT_FILL = 0.18F;

    private static final float NEXT_LINE = 0.55F;

    /** How far past the block behind it a post is still taken, squared: it stands on that block's face. */
    private static final double POST_SLACK = 0.25D;

    private static final List<io.github.izakyl.folkways.front.engine.colony.ColonyPath> PATHS = new ArrayList<>();

    public static void overview(List<net.minecraft.nbt.CompoundTag> paths) {
        PATHS.clear();
        paths.forEach(tag -> io.github.izakyl.folkways.front.engine.colony.ColonyPath.load(
            io.github.izakyl.folkways.core.api.persist.Reader.of(tag)).ifPresent(PATHS::add));
    }

    private static final List<BlockPos> POINTS = new ArrayList<>();

    /** The point the wheel moves: the last one set down, until another is picked up. */
    private static int held = -1;

    static {
        Drawing.install(Shape.Gesture.LINE, new Draft<Drawn>(PathSelection::drawn, PathSelection::clear,
            path -> Component.translatable("folkways.zoneconfig.drawn", path.points().size()),
            path -> path.points().size(),
            (colony, line, delegation, settings) -> new CreatePathPacket(colony, line.points(), delegation, settings),
            id -> PathOffers.of(id).map(offer -> (line, taking) -> offer.body().build(line.points(), taking))));
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
        held = -1;
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
        if (FolkwaysClientGameEvents.sneakDown(player)) {
            discard();
            return true;
        }
        if (FolkwaysClientGameEvents.modifierDown()) {
            aimedPoint(player).ifPresent(index -> {
                held = index;
                Draft.say(Component.translatable("folkways.path.held", index + 1));
            });
            return true;
        }
        Optional<BlockPos> aimed = Aim.cell(player);
        if (aimed.isEmpty()) {
            return true;
        }
        if (POINTS.size() >= MAX_POINTS) {
            Draft.say(Component.translatable("folkways.path.full", MAX_POINTS));
            return true;
        }
        POINTS.add(aimed.get());
        held = POINTS.size() - 1;
        Draft.say(Component.translatable("folkways.path.point", POINTS.size()));
        return true;
    }

    static void discard() {
        if (drafting()) {
            clear();
            Draft.say(Component.translatable("folkways.path.dropped"));
        }
    }

    /** Takes back the point set down last, and holds the one before it. */
    static void undo() {
        if (!POINTS.isEmpty()) {
            POINTS.removeLast();
            held = POINTS.size() - 1;
        }
    }

    static Optional<Component> status() {
        return POINTS.isEmpty() ? Optional.empty()
            : Optional.of(Component.translatable("folkways.zoneconfig.drawn", POINTS.size()));
    }

    public static boolean handleScroll(double verticalDelta) {
        Player player = Minecraft.getInstance().player;
        if (player == null || verticalDelta == 0.0D || held < 0) {
            return false;
        }
        int step = verticalDelta > 0.0D ? Mth.ceil(verticalDelta) : Mth.floor(verticalDelta);
        BlockPos moved = POINTS.get(held).relative(pull(player), step);
        POINTS.set(held, moved);
        // The player is told where the point shows, not the cell a ship keeps it in.
        BlockPos shown = Placed.shown(player.level(), moved);
        Draft.say(Component.translatable("folkways.path.moved", held + 1, shown.getX(), shown.getY(), shown.getZ()));
        return true;
    }

    /** Where a turn of the wheel draws the held point: towards the player, to their right with the other key, or up with both. */
    private static Direction pull(Player player) {
        boolean along = FolkwaysClientGameEvents.modifierDown();
        boolean across = FolkwaysClientGameEvents.alternateDown();
        if (along && across) {
            return Direction.UP;
        }
        Vec3 look = Aim.of(player).look();
        Direction ahead = look.horizontalDistanceSqr() < 1.0E-6D
            ? player.getDirection() : Direction.getNearest(look.x, 0.0D, look.z);
        return across ? ahead.getClockWise() : ahead.getOpposite();
    }

    /** The point of the draft whose post is under the crosshair, when it is not the one held already. */
    static Optional<Integer> aimedPoint(Player player) {
        Aim aim = Aim.of(player);
        Level level = player.level();
        // A block hit on a ship lands in the cells the ship keeps, and is measured from the eye as that ship sees it.
        double reach = aim.block(player)
            .map(hit -> hit.getLocation().distanceToSqr(aim.in(level, hit.getBlockPos()).from()))
            .orElse(Aim.range() * Aim.range()) + POST_SLACK;
        Integer best = null;
        for (int index = 0; index < POINTS.size(); index++) {
            Aim there = aim.in(level, POINTS.get(index));
            Optional<Vec3> hit = post(level, POINTS.get(index)).inflate(0.1D)
                .clip(there.from(), there.at(Aim.range()));
            if (index != held && hit.isPresent() && hit.get().distanceToSqr(there.from()) < reach) {
                reach = hit.get().distanceToSqr(there.from());
                best = index;
            }
        }
        return Optional.ofNullable(best);
    }

    private static AABB post(Level level, BlockPos point) {
        Vec3 foot = ColonyHighlightState.surface(level, point);
        return new AABB(foot.x - 0.3D, foot.y, foot.z - 0.3D, foot.x + 0.3D, foot.y + 0.6D, foot.z + 0.3D);
    }

    static Optional<java.util.UUID> aimedPath(Minecraft minecraft) {
        Optional<BlockHitResult> hit = Aim.of(minecraft.player).block(minecraft.player);
        if (hit.isEmpty() || !Layer.PATHS.shown()) {
            return Optional.empty();
        }
        Vec3 target = hit.get().getLocation();
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
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        // The draft is laid out as the road it would become: an amber ribbon on the ground through the points set
        // down so far, running on, fainter, to the block the player is aiming at. Points are kept where the level
        // stores them, and each piece is drawn where it shows: on a ship, with the ship.
        Optional<BlockPos> next = POINTS.isEmpty() || POINTS.size() >= MAX_POINTS ? Optional.empty() : aimed(minecraft);
        Set<BlockPos> laid = ColonyHighlightState.ribbon(level, POINTS);
        VertexConsumer fills = buffers.getBuffer(RenderType.debugFilledBox());
        for (BlockPos cell : laid) {
            ColonyHighlightState.ribbonCell(level, poseStack, fills, cell, DRAFT, ColonyHighlightState.RIBBON_FILL,
                partialTick);
        }
        next.ifPresent(aim -> {
            for (BlockPos cell : ColonyHighlightState.ribbon(level, List.of(POINTS.getLast(), aim))) {
                if (!laid.contains(cell)) {
                    ColonyHighlightState.ribbonCell(level, poseStack, fills, cell, DRAFT, NEXT_FILL, partialTick);
                }
            }
        });
        buffers.endBatch(RenderType.debugFilledBox());

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (var path : Layer.PATHS.shown() ? PATHS : List.<io.github.izakyl.folkways.front.engine.colony.ColonyPath>of()) {
            for (int at = 1; at < path.points().size(); at++) {
                BlockPos from = path.points().get(at - 1);
                BlockPos to = path.points().get(at);
                if (Placed.at(level, from, partialTick).centre().distanceToSqr(camera) < 4096
                        || Placed.at(level, to, partialTick).centre().distanceToSqr(camera) < 4096) {
                    stretch(level, poseStack, lines, from, to, SET, 1.0F, partialTick);
                }
            }
        }
        // Each point set down stands as a short post on the ribbon, so it reads apart from the cursor's own outline.
        // The held one stands out in the colour of what is already set.
        for (int index = 0; index < POINTS.size(); index++) {
            float[] rgb = index == held ? SET : DRAFT;
            BlockPos point = POINTS.get(index);
            Placed.drawAt(level, poseStack, point, partialTick, () ->
                LevelRenderer.renderLineBox(poseStack, lines, post(level, point), rgb[0], rgb[1], rgb[2], 1.0F));
        }
        for (int at = 1; at < POINTS.size(); at++) {
            stretch(level, poseStack, lines, POINTS.get(at - 1), POINTS.get(at), DRAFT, 1.0F, partialTick);
        }
        next.ifPresent(aim -> stretch(level, poseStack, lines, POINTS.getLast(), aim, DRAFT, NEXT_LINE, partialTick));
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }

    // The line between two points over their ribbon, where it shows; none between a ship and what is off it.
    private static void stretch(Level level, PoseStack poseStack, VertexConsumer lines, BlockPos from, BlockPos to,
            float[] rgb, float alpha, float partialTick) {
        if (Placed.together(level, from, to)) {
            Placed.drawAt(level, poseStack, from, partialTick, () -> segment(poseStack, lines,
                ColonyHighlightState.surface(level, from), ColonyHighlightState.surface(level, to), rgb, alpha));
        }
    }

    private static Optional<BlockPos> aimed(Minecraft minecraft) {
        return Aim.cell(minecraft.player);
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
