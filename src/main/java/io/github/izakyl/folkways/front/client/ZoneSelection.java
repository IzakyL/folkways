package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.item.HeldBook;
import io.github.izakyl.folkways.front.engine.net.CreateZonePacket;
import io.github.izakyl.folkways.front.engine.net.ZoneSnapshot;
import io.github.izakyl.folkways.front.api.ui.BoxOffers;
import io.github.izakyl.folkways.front.ui.screen.Drawing;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
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
    private static final double AIM_RANGE = 64.0D;
    private static final int MIN_RANGE = 1;
    private static final int MAX_RANGE = 100;
    private static final int DEFAULT_RANGE = 5;

    private static final float[] DRAFT = {0.95F, 0.95F, 0.95F};

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
            box -> Component.translatable("folkways.zoneconfig.size", box.sizeX(), box.sizeY(), box.sizeZ())
                .append(" ")
                .append(Component.translatable("folkways.zoneconfig.corner",
                    box.min().getX(), box.min().getY(), box.min().getZ())),
            (colony, box, delegation, settings) -> new CreateZonePacket(colony, box.min(), box.max(), delegation, settings),
            id -> BoxOffers.of(id).<Consumer<Marked>>map(offer -> box -> offer.take().take(box.min(), box.max()))));
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
        if (minecraft.screen != null || min == null) {
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

        if (player.isShiftKeyDown()) {
            if (min != null || origin != null) {
                clear();
                Draft.say(Component.translatable("folkways.zone.dropped"));
            }
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
        int step = verticalDelta > 0.0D ? Mth.ceil(verticalDelta) : Mth.floor(verticalDelta);
        if (min == null) {
            range = Mth.clamp(range + step, MIN_RANGE, MAX_RANGE);
            Draft.say(Component.translatable("folkways.zone.reach", range));
            return true;
        }
        if (aimedFace == null) {
            return true;
        }
        pushFace(minecraft, aimedFace, step);
        Draft.say(Component.translatable("folkways.zone.marked",
            max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1));
        return true;
    }

    private static void pushFace(Minecraft minecraft, Direction face, int step) {
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        int signed = boxOfMarked().contains(camera) ? -step : step;
        int axisStep = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? signed : -signed;
        BlockPos shifted = new BlockPos(
            face.getAxis() == Direction.Axis.X ? axisStep : 0,
            face.getAxis() == Direction.Axis.Y ? axisStep : 0,
            face.getAxis() == Direction.Axis.Z ? axisStep : 0);
        if (face.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            max = clampAbove(max.offset(shifted), min, face.getAxis());
        } else {
            min = clampBelow(min.offset(shifted), max, face.getAxis());
        }
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
            return BlockPos.containing(player.getEyePosition().add(player.getLookAngle().scale(range)));
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hit.getBlockPos().immutable();
    }

    private static Direction faceUnderCrosshair(Player player) {
        AABB box = boxOfMarked();
        Vec3 eye = player.getEyePosition();
        if (box.contains(eye)) {
            return Direction.getNearest(player.getLookAngle());
        }
        Vec3 end = eye.add(player.getLookAngle().scale(AIM_RANGE));
        BlockHitResult hit = AABB.clip(List.of(box), eye, end, BlockPos.ZERO);
        return hit == null || hit.getType() != HitResult.Type.BLOCK ? null : hit.getDirection();
    }

    static Optional<ZoneSnapshot> aimedZone(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(AIM_RANGE));
        ZoneSnapshot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ZoneSnapshot zone : ColonyHighlightState.zones()) {
            AABB box = AABB.encapsulatingFullBlocks(zone.min(), zone.max());
            Optional<Vec3> hit = box.clip(eye, end);
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

    public static Optional<ZoneSnapshot> focusedZone() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || HeldBook.bound(minecraft.player).isEmpty()) {
            return Optional.empty();
        }
        return aimedZone(minecraft.player);
    }

    private static AABB boxOfMarked() {
        return AABB.encapsulatingFullBlocks(min, max);
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
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
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        if (min != null && aimedFace != null) {
            VertexConsumer fills = buffers.getBuffer(RenderType.debugFilledBox());
            AABB slab = faceSlab(boxOfMarked(), aimedFace);
            LevelRenderer.addChainedFilledBoxVertices(poseStack, fills,
                slab.minX, slab.minY, slab.minZ, slab.maxX, slab.maxY, slab.maxZ,
                DRAFT[0], DRAFT[1], DRAFT[2], 0.35F);
            buffers.endBatch(RenderType.debugFilledBox());
        }

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        if (min != null) {
            LevelRenderer.renderLineBox(poseStack, lines, boxOfMarked().inflate(0.03D),
                DRAFT[0], DRAFT[1], DRAFT[2], 1.0F);
        } else {
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(origin).inflate(0.025D),
                DRAFT[0], DRAFT[1], DRAFT[2], 1.0F);
        }
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
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
