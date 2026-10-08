package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.izakyl.folkways.front.api.Placard;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.engine.colony.ColonyPath;
import io.github.izakyl.folkways.front.engine.item.HeldBook;
import io.github.izakyl.folkways.front.engine.net.ColonyOverviewPacket;
import io.github.izakyl.folkways.front.engine.net.RequestColonyOverviewPacket;
import io.github.izakyl.folkways.front.engine.net.ZoneSnapshot;
import io.github.izakyl.folkways.front.ui.UiRegistrations;
import io.github.izakyl.folkways.front.ui.screen.DomainPage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ColonyHighlightState {
    private static final int REQUEST_INTERVAL_TICKS = 20;
    private static final double OUTLINE_RANGE = 160.0D;
    private static final double FILL_RANGE = 128.0D;
    private static final double CARD_RANGE = 128.0D;
    private static final float CARD_SCALE = 0.025F;
    private static final double CARD_HEAD_ROOM = 0.6D;
    private static final double PATH_HEAD_ROOM = 1.8D;
    private static final double PLACARD_RANGE = 160.0D;
    static final float[] PLACARD_RGB = {0.98F, 0.74F, 0.30F};
    private static final float PLACARD_FILL = 0.30F;
    static final float RIBBON_FILL = 0.45F;
    private static final double RIBBON_HEIGHT = 0.12D;
    private static final double MEMBER_RANGE = 48.0D;
    private static final double MEMBER_RANGE_SQR = MEMBER_RANGE * MEMBER_RANGE;
    private static final long PING_WINDOW_MS = 5000L;
    private static final float SATURATION = 0.62F;
    private static final float VALUE = 0.92F;

    private static final List<BlockPos> MEMBERS = new ArrayList<>();
    private static final List<ZoneSnapshot> ZONES = new ArrayList<>();
    private static final Map<UUID, List<Line>> NOTES = new HashMap<>();
    private static final List<Placard> PLACARDS = new ArrayList<>();
    private static UUID activeColonyId;
    private static int requestCooldown;
    private static BlockPos pinged;
    private static long pingUntilMs;

    static {
        DomainPage.install(ColonyHighlightState::pingBlock);
    }

    private ColonyHighlightState() {
    }

    public static void handleColonyOverview(ColonyOverviewPacket packet) {
        PathSelection.overview(packet.paths());
        MEMBERS.clear();
        MEMBERS.addAll(packet.members());
        ZONES.clear();
        ZONES.addAll(packet.zones());
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            GhostRenderer.show(level, packet.ghosts());
        }
        NOTES.clear();
        packet.notes().forEach(note -> NOTES.put(note.id(), note.lines()));
        PLACARDS.clear();
        PLACARDS.addAll(packet.placards());
    }

    public static void pingBlock(BlockPos pos) {
        pinged = pos.immutable();
        pingUntilMs = Util.getMillis() + PING_WINDOW_MS;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null && minecraft.player != null) {
            minecraft.player.closeContainer();
        }
    }

    public static List<ZoneSnapshot> zones() {
        return List.copyOf(ZONES);
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            reset();
            return;
        }

        UUID colonyId = heldColonyId(minecraft.player);
        if (colonyId == null) {
            reset();
            return;
        }

        if (!colonyId.equals(activeColonyId)) {
            activeColonyId = colonyId;
            MEMBERS.clear();
            ZONES.clear();
            PathSelection.overview(List.of());
            GhostRenderer.clear();
            NOTES.clear();
            PLACARDS.clear();
            requestCooldown = 0;
        }

        if (requestCooldown <= 0 && minecraft.getConnection() != null) {
            requestCooldown = REQUEST_INTERVAL_TICKS;
            PacketDistributor.sendToServer(new RequestColonyOverviewPacket(colonyId));
        } else {
            requestCooldown--;
        }
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
            || (MEMBERS.isEmpty() && ZONES.isEmpty() && GhostRenderer.isEmpty() && PLACARDS.isEmpty()
                && NOTES.isEmpty() && pingedBlock() == null)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || heldColonyId(minecraft.player) == null) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Level level = minecraft.level;

        GhostRenderer.drawModels(level, camera, partialTick);

        VertexConsumer fills = buffers.getBuffer(RenderType.debugFilledBox());
        drawZoneFills(level, poseStack, fills, camera, partialTick);
        drawPlacardFills(level, poseStack, fills, camera);
        GhostRenderer.drawFills(level, poseStack, fills, partialTick);
        buffers.endBatch(RenderType.debugFilledBox());

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        drawMemberBoxes(level, poseStack, lines, camera, partialTick);
        drawZoneOutlines(level, poseStack, lines, camera, partialTick);
        drawPlacardOutlines(level, poseStack, lines, camera);
        GhostRenderer.drawOutlines(level, poseStack, lines, partialTick);
        BlockPos pointed = pingedBlock();
        if (pointed != null) {
            Placed.at(level, pointed, partialTick).enter(poseStack);
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(BlockPos.ZERO).inflate(0.05D),
                1.0F, 1.0F, 1.0F, 1.0F);
            poseStack.popPose();
        }
        buffers.endBatch(RenderType.lines());

        queueCards(minecraft, camera, partialTick);
        poseStack.popPose();
        buffers.endBatch();
    }

    private static void drawZoneFills(Level level, PoseStack poseStack, VertexConsumer fills, Vec3 viewer,
            float partialTick) {
        for (ZoneSnapshot zone : ZONES) {
            Placed placed = Placed.at(level, zone.min(), partialTick);
            AABB box = localBoxOf(zone);
            if (Math.sqrt(placed.point(box.getCenter()).distanceToSqr(viewer)) > FILL_RANGE) {
                continue;
            }
            float[] rgb = shaded(colorOf(zone), zone.id());
            placed.enter(poseStack);
            LevelRenderer.addChainedFilledBoxVertices(poseStack, fills,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, rgb[0], rgb[1], rgb[2], 0.16F);
            poseStack.popPose();
        }
    }

    private static void drawMemberBoxes(Level level, PoseStack poseStack, VertexConsumer lines,
            Vec3 viewer, float partialTick) {
        for (BlockPos pos : MEMBERS) {
            Placed placed = Placed.at(level, pos, partialTick);
            if (placed.centre().distanceToSqr(viewer) > MEMBER_RANGE_SQR) {
                continue;
            }
            placed.enter(poseStack);
            LevelRenderer.renderLineBox(poseStack, lines,
                memberBox(level, pos).move(-pos.getX(), -pos.getY(), -pos.getZ()).inflate(0.02D),
                0.30F, 0.60F, 0.95F, 1.0F);
            poseStack.popPose();
        }
    }

    private static AABB memberBox(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return UiRegistrations.partner(level, pos, state)
            .map(other -> shapeBox(level, state, pos).minmax(shapeBox(level, level.getBlockState(other), other)))
            .orElseGet(() -> new AABB(pos));
    }

    private static AABB shapeBox(BlockGetter level, BlockState state, BlockPos pos) {
        VoxelShape shape = state.getShape(level, pos);
        return shape.isEmpty()
            ? new AABB(pos)
            : shape.bounds().move(pos.getX(), pos.getY(), pos.getZ());
    }

    private static void drawZoneOutlines(Level level, PoseStack poseStack, VertexConsumer lines, Vec3 viewer,
            float partialTick) {
        for (ZoneSnapshot zone : ZONES) {
            Placed placed = Placed.at(level, zone.min(), partialTick);
            AABB box = localBoxOf(zone);
            double distance = Math.sqrt(placed.point(box.getCenter()).distanceToSqr(viewer));
            if (distance > OUTLINE_RANGE) {
                continue;
            }
            float[] rgb = shaded(colorOf(zone), zone.id());
            float alpha = (float) Math.max(0.35D, 1.0D - distance / OUTLINE_RANGE);
            placed.enter(poseStack);
            LevelRenderer.renderLineBox(poseStack, lines, box.inflate(0.02D),
                rgb[0], rgb[1], rgb[2], alpha);
            poseStack.popPose();
        }
    }

    private static float[] shaded(float[] rgb, UUID id) {
        float factor = (id.getLeastSignificantBits() & 1L) == 0L ? 1.0F : 0.72F;
        return new float[] {rgb[0] * factor, rgb[1] * factor, rgb[2] * factor};
    }

    private static void drawPlacardFills(BlockGetter level, PoseStack poseStack, VertexConsumer fills,
            Vec3 camera) {
        for (Placard placard : PLACARDS) {
            switch (placard.outline()) {
                case Placard.Outline.Box box -> {
                    AABB bounds = AABB.encapsulatingFullBlocks(box.min(), box.max());
                    if (bounds.getCenter().distanceTo(camera) <= PLACARD_RANGE) {
                        LevelRenderer.addChainedFilledBoxVertices(poseStack, fills, bounds.minX, bounds.minY + 0.02D,
                            bounds.minZ, bounds.maxX, bounds.minY + RIBBON_HEIGHT, bounds.maxZ,
                            PLACARD_RGB[0], PLACARD_RGB[1], PLACARD_RGB[2], PLACARD_FILL);
                    }
                }
                case Placard.Outline.Path path -> {
                    for (BlockPos cell : ribbon(path.points())) {
                        if (Vec3.atCenterOf(cell).distanceTo(camera) > PLACARD_RANGE) {
                            continue;
                        }
                        ribbonCell(level, poseStack, fills, cell, PLACARD_RGB, RIBBON_FILL);
                    }
                }
            }
        }
    }

    static void ribbonCell(BlockGetter level, PoseStack poseStack, VertexConsumer fills, BlockPos cell,
            float[] rgb, float alpha) {
        double floor = floorOf(level, cell);
        LevelRenderer.addChainedFilledBoxVertices(poseStack, fills, cell.getX(), floor + 0.02D,
            cell.getZ(), cell.getX() + 1.0D, floor + RIBBON_HEIGHT, cell.getZ() + 1.0D,
            rgb[0], rgb[1], rgb[2], alpha);
    }

    /** The middle of a ribbon's top face over a cell, where a line along the ribbon shows above the ground. */
    static Vec3 surface(BlockGetter level, BlockPos cell) {
        return new Vec3(cell.getX() + 0.5D, floorOf(level, cell) + RIBBON_HEIGHT, cell.getZ() + 0.5D);
    }

    private static double floorOf(BlockGetter level, BlockPos cell) {
        return level.getBlockState(cell).isAir() ? cell.getY() : cell.getY() + 1.0D;
    }

    static Set<BlockPos> ribbon(List<BlockPos> points) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int at = 1; at < points.size(); at++) {
            BlockPos from = points.get(at - 1);
            BlockPos to = points.get(at);
            int steps = Math.max(1, from.distManhattan(to));
            for (int step = 0; step <= steps; step++) {
                double t = step / (double) steps;
                cells.add(BlockPos.containing(Mth.lerp(t, from.getX(), to.getX()) + 0.5D,
                    Mth.lerp(t, from.getY(), to.getY()) + 0.5D, Mth.lerp(t, from.getZ(), to.getZ()) + 0.5D));
            }
        }
        if (points.size() == 1) {
            cells.add(points.getFirst());
        }
        return cells;
    }

    private static void drawPlacardOutlines(BlockGetter level, PoseStack poseStack, VertexConsumer lines,
            Vec3 camera) {
        for (Placard placard : PLACARDS) {
            switch (placard.outline()) {
                case Placard.Outline.Box box -> {
                    AABB bounds = AABB.encapsulatingFullBlocks(box.min(), box.max());
                    if (bounds.getCenter().distanceTo(camera) <= PLACARD_RANGE) {
                        LevelRenderer.renderLineBox(poseStack, lines, bounds.inflate(0.02D),
                            PLACARD_RGB[0], PLACARD_RGB[1], PLACARD_RGB[2], 1.0F);
                    }
                }
                case Placard.Outline.Path path -> {
                    List<BlockPos> points = path.points();
                    for (int at = 0; at < points.size(); at++) {
                        Vec3 here = Vec3.atCenterOf(points.get(at));
                        if (here.distanceTo(camera) > PLACARD_RANGE) {
                            continue;
                        }
                        LevelRenderer.renderLineBox(poseStack, lines, new AABB(points.get(at)).inflate(0.02D),
                            PLACARD_RGB[0], PLACARD_RGB[1], PLACARD_RGB[2], 1.0F);
                        if (at > 0) {
                            PathSelection.segment(poseStack, lines, surface(level, points.get(at - 1)),
                                surface(level, points.get(at)), PLACARD_RGB, 1.0F);
                        }
                    }
                }
            }
        }
    }

    /** Queues the zone, path and placard cards; {@link WorldCards#flush} draws them with the residents' cards. */
    private static void queueCards(Minecraft minecraft, Vec3 camera, float partialTick) {
        for (ZoneSnapshot zone : ZONES) {
            Placed placed = Placed.at(minecraft.level, zone.min(), partialTick);
            AABB box = localBoxOf(zone);
            Vec3 center = box.getCenter();
            List<Line> lines = new ArrayList<>();
            lines.add(Line.told(DelegationNames.told(zone.delegation())));
            lines.addAll(NOTES.getOrDefault(zone.id(), List.of()));
            card(placed.point(new Vec3(center.x, box.maxY + CARD_HEAD_ROOM, center.z)), lines, camera);
        }
        for (ColonyPath path : PathSelection.paths()) {
            List<Line> noted = NOTES.get(path.id());
            if (noted == null || path.points().isEmpty()) {
                continue;
            }
            List<Line> lines = new ArrayList<>();
            lines.add(Line.told(DelegationNames.told(path.delegation())));
            lines.addAll(noted);
            card(middleOf(path.points(), PATH_HEAD_ROOM), lines, camera);
        }
        for (Placard placard : PLACARDS) {
            Vec3 anchor = switch (placard.outline()) {
                case Placard.Outline.Box box -> {
                    AABB bounds = AABB.encapsulatingFullBlocks(box.min(), box.max());
                    yield new Vec3(bounds.getCenter().x, bounds.maxY + CARD_HEAD_ROOM, bounds.getCenter().z);
                }
                case Placard.Outline.Path path -> middleOf(path.points(), PATH_HEAD_ROOM);
            };
            card(anchor, placard.lines(), camera);
        }
    }

    private static void card(Vec3 anchor, List<Line> lines, Vec3 camera) {
        if (anchor.distanceTo(camera) > CARD_RANGE) {
            return;
        }
        List<ColonyLookCard.Row> rows = ColonyLookCard.rows(lines);
        if (!rows.isEmpty()) {
            WorldCards.queue(anchor, rows, CARD_SCALE, WorldCards.Look.PLACARD, camera);
        }
    }

    private static Vec3 middleOf(List<BlockPos> points, double headRoom) {
        BlockPos middle = points.get(points.size() / 2);
        return Vec3.atBottomCenterOf(middle).add(0.0D, headRoom, 0.0D);
    }

    private static AABB localBoxOf(ZoneSnapshot zone) {
        return AABB.encapsulatingFullBlocks(BlockPos.ZERO, zone.max().subtract(zone.min()));
    }

    private static float[] colorOf(ZoneSnapshot zone) {
        float hue = Math.floorMod(zone.delegation().hashCode(), 360) / 60.0F;
        float chroma = SATURATION * VALUE;
        float second = chroma * (1.0F - Math.abs(hue % 2.0F - 1.0F));
        float floor = VALUE - chroma;
        return switch ((int) hue) {
            case 0 -> new float[] {chroma + floor, second + floor, floor};
            case 1 -> new float[] {second + floor, chroma + floor, floor};
            case 2 -> new float[] {floor, chroma + floor, second + floor};
            case 3 -> new float[] {floor, second + floor, chroma + floor};
            case 4 -> new float[] {second + floor, floor, chroma + floor};
            default -> new float[] {chroma + floor, floor, second + floor};
        };
    }

    private static BlockPos pingedBlock() {
        return pinged != null && Util.getMillis() <= pingUntilMs ? pinged : null;
    }

    private static void reset() {
        MEMBERS.clear();
        ZONES.clear();
        PathSelection.overview(List.of());
        GhostRenderer.clear();
        NOTES.clear();
        PLACARDS.clear();
        activeColonyId = null;
        requestCooldown = 0;
    }

    static UUID heldColonyId(Player player) {
        return HeldBook.bound(player).map(HeldBook::colonyId).orElse(null);
    }
}
