package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.izakyl.folkways.FolkwaysClientConfig;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.item.HeldBook;
import io.github.izakyl.folkways.front.engine.net.CreateZonePacket;
import io.github.izakyl.folkways.front.engine.net.ZoneSnapshot;
import io.github.izakyl.folkways.front.api.ui.BoxOffers;
import io.github.izakyl.folkways.front.ui.screen.Drawing;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class ZoneSelection {
    private static final int MIN_RANGE = 1;
    private static final int MAX_RANGE = 100;
    private static final int DEFAULT_RANGE = 5;

    private static final float[] DRAFT = {0.95F, 0.95F, 0.95F};
    private static final float PREVIEW = 0.45F;

    private static BlockPos origin;
    private static BlockPos min;
    private static BlockPos max;
    private static Direction aimedFace;
    private static int range = DEFAULT_RANGE;

    private static final long OPENING_WINDOW_MS = 2000L;
    private static UUID openingId;
    private static long openingUntilMs;

    static {
        Drawing.install(Shape.Gesture.BOX, new Draft<Marked>(ZoneSelection::marked, ZoneSelection::clear,
            ZoneSelection::measure, box -> box.sizeX() * box.sizeY() * box.sizeZ(),
            (colony, box, delegation, settings) -> new CreateZonePacket(colony, box.min(), box.max(), delegation, settings),
            id -> BoxOffers.of(id).map(offer -> (box, taking) -> offer.body().build(box.min(), box.max(), taking))));
        Drawing.offers(Shape.Gesture.BOX, () -> BoxOffers.all().stream()
            .map(offer -> new Drawing.Offered(offer.id(), offer.nameKey(), offer.icon()))
            .toList());
        Drawing.selection(ZoneSelection::takeOpening);
    }

    private ZoneSelection() {
    }

    public record Marked(BlockPos min, BlockPos max) {
        public int sizeX() {
            return max.getX() - min.getX() + 1;
        }

        public int sizeY() {
            return max.getY() - min.getY() + 1;
        }

        public int sizeZ() {
            return max.getZ() - min.getZ() + 1;
        }
    }

    public static Optional<Marked> marked() {
        if (min == null || max == null) {
            return Optional.empty();
        }
        return Optional.of(new Marked(min, max));
    }

    public static void clear() {
        origin = null;
        min = null;
        max = null;
        aimedFace = null;
    }

    public static boolean drafting() {
        return origin != null || min != null;
    }

    static void discard() {
        if (drafting()) {
            clear();
            Draft.say(Component.translatable("folkways.zone.dropped"));
        }
    }

    /** How far the box being marked has got, in a line: its size and corner, or that it has one corner so far. */
    static Optional<Component> status() {
        if (min == null) {
            return origin == null ? Optional.empty() : Optional.of(Component.translatable("folkways.zone.corner"));
        }
        return marked().map(ZoneSelection::measure);
    }

    private static Component measure(Marked box) {
        BlockPos corner = shown(box.min());
        return Component.translatable("folkways.zoneconfig.size", box.sizeX(), box.sizeY(), box.sizeZ())
            .append(" ")
            .append(Component.translatable("folkways.zoneconfig.corner", corner.getX(), corner.getY(), corner.getZ()));
    }

    static int reach() {
        return range;
    }

    static void openOn(UUID id) {
        openingId = id;
        openingUntilMs = Util.getMillis() + OPENING_WINDOW_MS;
    }

    private static UUID takeOpening() {
        UUID id = Util.getMillis() <= openingUntilMs ? openingId : null;
        openingId = null;
        return id;
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null) {
            clear();
            return;
        }
        Optional<HeldBook> book = HeldBook.bound(player);
        if (book.isEmpty() || book.get().gesture() != Shape.Gesture.BOX) {
            clear();
            return;
        }
        if (Overhead.screened(minecraft) || min == null) {
            aimedFace = null;
            return;
        }
        aimedFace = faceUnderCrosshair(player);
    }

    public static boolean handleLeftClick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            return false;
        }
        Player player = minecraft.player;

        if (FolkwaysClientGameEvents.sneakDown(player)) {
            discard();
            return true;
        }

        BlockPos clicked = aimedCell(player);
        if (clicked == null) {
            return true;
        }
        if (min != null) {
            clear();
        }
        if (origin == null) {
            origin = clicked;
            Draft.say(Component.translatable("folkways.zone.corner"));
            return true;
        }
        setBox(origin, clicked);
        origin = null;
        Draft.say(Component.translatable("folkways.zone.marked",
            max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1));
        return true;
    }

    public static boolean handleScroll(double verticalDelta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || verticalDelta == 0.0D) {
            return false;
        }
        int step = FolkwaysClientConfig.away(verticalDelta > 0.0D ? Mth.ceil(verticalDelta) : Mth.floor(verticalDelta));
        if (min == null) {
            if (!FolkwaysClientGameEvents.modifierDown()) {
                return false;
            }
            range = Mth.clamp(range + step, MIN_RANGE, MAX_RANGE);
            Draft.say(Component.translatable("folkways.zone.reach", range));
            return true;
        }
        if (aimedFace == null) {
            return true;
        }
        // The wheel pushes away from the player whatever it has hold of, as Create's quill does, so a box entered is
        // pushed the other way.
        Direction away = boxOfMarked().contains(Aim.of(minecraft.player).in(minecraft.player.level(), min).from())
            ? aimedFace : aimedFace.getOpposite();
        int by = away.getAxisDirection().getStep() * step;
        Direction.Axis axis = aimedFace.getAxis();
        if (gripped().size() > 1) {
            min = min.relative(axis, by);
            max = max.relative(axis, by);
            BlockPos corner = shown(min);
            Draft.say(Component.translatable("folkways.zone.shifted", corner.getX(), corner.getY(), corner.getZ()));
            return true;
        }
        if (gripped().getFirst().getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            max = clampAbove(max.relative(axis, by), min, axis);
        } else {
            min = clampBelow(min.relative(axis, by), max, axis);
        }
        Draft.say(Component.translatable("folkways.zone.marked",
            max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1));
        return true;
    }

    /** The faces the wheel has hold of: the one aimed at, the one behind it with the other key, or both at once. */
    private static List<Direction> gripped() {
        boolean near = FolkwaysClientGameEvents.modifierDown();
        boolean far = FolkwaysClientGameEvents.alternateDown();
        if (near && far) {
            return List.of(aimedFace, aimedFace.getOpposite());
        }
        return List.of(far ? aimedFace.getOpposite() : aimedFace);
    }

    private static BlockPos clampAbove(BlockPos moved, BlockPos floor, Direction.Axis axis) {
        return new BlockPos(
            axis == Direction.Axis.X ? Math.max(moved.getX(), floor.getX()) : moved.getX(),
            axis == Direction.Axis.Y ? Math.max(moved.getY(), floor.getY()) : moved.getY(),
            axis == Direction.Axis.Z ? Math.max(moved.getZ(), floor.getZ()) : moved.getZ());
    }

    private static BlockPos clampBelow(BlockPos moved, BlockPos ceiling, Direction.Axis axis) {
        return new BlockPos(
            axis == Direction.Axis.X ? Math.min(moved.getX(), ceiling.getX()) : moved.getX(),
            axis == Direction.Axis.Y ? Math.min(moved.getY(), ceiling.getY()) : moved.getY(),
            axis == Direction.Axis.Z ? Math.min(moved.getZ(), ceiling.getZ()) : moved.getZ());
    }

    private static void setBox(BlockPos first, BlockPos second) {
        min = BlockPos.min(first, second);
        max = BlockPos.max(first, second);
    }

    private static BlockPos aimedCell(Player player) {
        if (FolkwaysClientGameEvents.modifierDown()) {
            // A cell in the air, at the reach the wheel set: on the ship the box is being marked on, if it is.
            BlockPos marking = origin != null ? origin : min;
            Aim aim = marking == null ? Aim.of(player) : Aim.of(player).in(player.level(), marking);
            return BlockPos.containing(aim.at(range));
        }
        return Aim.cell(player).orElse(null);
    }

    /** The cell a click would take as a corner, while there is a corner still to set. */
    static Optional<BlockPos> cursor(Player player) {
        return min != null ? Optional.empty() : Optional.ofNullable(aimedCell(player));
    }

    private static Direction faceUnderCrosshair(Player player) {
        AABB box = boxOfMarked();
        Aim aim = Aim.of(player).in(player.level(), min);
        if (box.contains(aim.from())) {
            return Direction.getNearest(aim.look());
        }
        BlockHitResult hit = AABB.clip(List.of(box), aim.from(), aim.at(Aim.range()), BlockPos.ZERO);
        return hit == null || hit.getType() != HitResult.Type.BLOCK ? null : hit.getDirection();
    }

    static Optional<ZoneSnapshot> aimedZone(Player player) {
        if (!Layer.ZONES.shown()) {
            return Optional.empty();
        }
        Aim seen = Aim.of(player);
        ZoneSnapshot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ZoneSnapshot zone : ColonyHighlightState.zones()) {
            // A zone keeps the cells of the ship it lies on, if it does: the ray is met there.
            Aim aim = seen.in(player.level(), zone.min());
            Vec3 eye = aim.from();
            AABB box = AABB.encapsulatingFullBlocks(zone.min(), zone.max());
            Optional<Vec3> hit = box.clip(eye, aim.at(Aim.range()));
            if (hit.isEmpty()) {
                continue;
            }
            double distance = eye.distanceToSqr(hit.get());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = zone;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Where a cell of the box shows, for the player to be told: the cell itself unless the box is on a ship. */
    private static BlockPos shown(BlockPos cell) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? cell : Placed.shown(minecraft.level, cell);
    }

    private static AABB boxOfMarked() {
        return AABB.encapsulatingFullBlocks(min, max);
    }

    public static void render(RenderLevelStageEvent event) {
        // Draw against solid terrain before water writes its depth, as the native block outline does.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
            || (origin == null && min == null)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        // The box is drawn where it shows: on a ship, moved and turned with it from the cells the ship keeps.
        BlockPos anchor = min != null ? min : origin;
        Placed.at(minecraft.level, anchor, event.getPartialTick().getGameTimeDeltaPartialTick(false)).enter(poseStack);
        poseStack.translate(-anchor.getX(), -anchor.getY(), -anchor.getZ());
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        if (min != null && aimedFace != null) {
            VertexConsumer fills = buffers.getBuffer(Layers.FACE_FILL);
            for (Direction face : gripped()) {
                AABB slab = faceSlab(boxOfMarked(), face);
                LevelRenderer.addChainedFilledBoxVertices(poseStack, fills,
                    slab.minX, slab.minY, slab.minZ, slab.maxX, slab.maxY, slab.maxZ,
                    DRAFT[0], DRAFT[1], DRAFT[2], 0.35F);
            }
            buffers.endBatch(Layers.FACE_FILL);
        }

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        if (min != null) {
            LevelRenderer.renderLineBox(poseStack, lines, boxOfMarked().inflate(0.03D),
                DRAFT[0], DRAFT[1], DRAFT[2], 1.0F);
        } else {
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(origin).inflate(0.025D),
                DRAFT[0], DRAFT[1], DRAFT[2], 1.0F);
            // The box a click now would mark, from the corner set down to the cell aimed at.
            BlockPos opposite = aimedCell(minecraft.player);
            if (opposite != null) {
                LevelRenderer.renderLineBox(poseStack, lines,
                    AABB.encapsulatingFullBlocks(origin, opposite).inflate(0.03D),
                    DRAFT[0], DRAFT[1], DRAFT[2], PREVIEW);
            }
        }
        poseStack.popPose();
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }

    /** The face fill tests against terrain but leaves depth clear for later previews. */
    private static final class Layers extends RenderType {
        static final RenderType FACE_FILL = create("folkways_zone_face_fill", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLE_STRIP, 1536, false, true,
            CompositeState.builder()
                .setShaderState(POSITION_COLOR_SHADER)
                .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false));

        private Layers(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling,
                boolean sorted, Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sorted, setup, clear);
        }
    }

    private static AABB faceSlab(AABB box, Direction face) {
        double thickness = 0.06D;
        return switch (face) {
            case WEST -> new AABB(box.minX, box.minY, box.minZ, box.minX + thickness, box.maxY, box.maxZ);
            case EAST -> new AABB(box.maxX - thickness, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            case DOWN -> new AABB(box.minX, box.minY, box.minZ, box.maxX, box.minY + thickness, box.maxZ);
            case UP -> new AABB(box.minX, box.maxY - thickness, box.minZ, box.maxX, box.maxY, box.maxZ);
            case NORTH -> new AABB(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ + thickness);
            case SOUTH -> new AABB(box.minX, box.minY, box.maxZ - thickness, box.maxX, box.maxY, box.maxZ);
        };
    }
}
