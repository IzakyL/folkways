package io.github.izakyl.folkways.assistant.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.assistant.tool.api.ConfirmationQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Marks every change awaiting the player's decision where it stands in the world: an outline round the resident it
 * touches, a line through the points it draws, a box round the cells it builds on, or a facility's extent as it was
 * (faint) and as it will be (in clay), the cells it gains tinted clay and those it gives up tinted stone. The marks
 * carry no words; the review window names each change. Building ghosts are drawn by the shape preview; these marks
 * only frame them.
 */
@EventBusSubscriber(modid = AssistantMod.ID, value = Dist.CLIENT)
public final class PendingMarks {

    private static final float[] INK = {0xD9 / 255f, 0x77 / 255f, 0x57 / 255f};

    private static final float[] STONE = {0xB8 / 255f, 0xB3 / 255f, 0xA7 / 255f};

    private static final int MAX_PIECES = 256;

    private static List<ConfirmationQueue.Change> shown = List.of();

    private static ConfirmationQueue.Change focused;

    private static Entity viewpoint;

    private static Entity before;

    private static long focusedAt;

    private PendingMarks() {
    }

    static void show(List<ConfirmationQueue.Change> changes) {
        shown = List.copyOf(changes);
        if (focused != null && !shown.contains(focused)) {
            focused = null;
            release();
        }
    }

    static void clear() {
        show(List.of());
    }

    /** Whether the change has a place in the world the player is in. */
    static boolean placed(ConfirmationQueue.Change change) {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && region(level, change).isPresent();
    }

    /**
     * Brings the change into view beside the windows: at the given share of the screen's width, inside a clear span
     * the given share of the screen wide. A change that fits there and stands in plain sight is faced by turning the
     * player; one too large to fit from where the player stands, or hidden behind blocks, is framed from a viewpoint
     * pulled back and above it, held until the change is decided or the windows close. Its mark pulses a moment.
     */
    static void locate(ConfirmationQueue.Change change, float across, float span) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Optional<AABB> found = region(minecraft.level, change);
        if (found.isEmpty()) {
            return;
        }
        focused = change;
        focusedAt = System.currentTimeMillis();
        AABB box = found.get();
        Vec3 target = box.getCenter();
        var window = minecraft.getWindow();
        double aspect = window.getWidth() / (double) Math.max(1, window.getHeight());
        double halfHigh = Math.toRadians(minecraft.options.fov().get()) / 2;
        double halfWide = Math.atan(Math.tan(halfHigh) * aspect);
        float place = Mth.clamp(across, 0.05f, 0.95f);
        float aside = (float) Math.toDegrees(Math.atan((1 - 2 * place) * Math.tan(halfWide)));
        // How far back the whole box fits: its half-diagonal against the clear span across and the height of the view.
        double reach = Math.max(1.5, Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
                + box.getZsize() * box.getZsize()) / 2);
        double room = Math.max(0.05, Mth.clamp(span, 0.1f, 1f) * Math.tan(halfWide));
        double fit = 1.15 * reach / Math.min(room, Math.tan(halfHigh));
        var player = minecraft.player;
        Vec3 eye = player.getEyePosition();
        if (eye.distanceTo(target) >= fit && inSight(minecraft.level, player, eye, box)) {
            release();
            float[] facing = facing(eye, target);
            player.setYRot(facing[0] + aside);
            player.setXRot(Mth.clamp(facing[1], -90f, 90f));
            player.yRotO = player.getYRot();
            player.xRotO = player.getXRot();
            player.setYHeadRot(player.getYRot());
            player.yHeadRotO = player.getYRot();
            return;
        }
        Vec3 back = new Vec3(eye.x - target.x, 0, eye.z - target.z);
        back = back.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, -1) : back.normalize();
        Vec3 from = target;
        for (double rise = Math.toRadians(35); rise < Math.toRadians(80); rise += Math.toRadians(10)) {
            from = target.add(back.scale(fit * Math.cos(rise))).add(0, fit * Math.sin(rise), 0);
            if (inSight(minecraft.level, player, from, box)) {
                break;
            }
        }
        float[] facing = facing(from, target);
        Marker eyeMark = new Marker(EntityType.MARKER, minecraft.level);
        eyeMark.moveTo(from.x, from.y - eyeMark.getEyeHeight(), from.z, facing[0] + aside, facing[1]);
        eyeMark.setOldPosAndRot();
        if (viewpoint == null) {
            before = minecraft.getCameraEntity();
        }
        viewpoint = eyeMark;
        minecraft.setCameraEntity(eyeMark);
        minecraft.levelRenderer.needsUpdate();
    }

    /** Gives the view back to the player if a framing viewpoint holds it. */
    static void release() {
        if (viewpoint == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getCameraEntity() == viewpoint) {
            minecraft.setCameraEntity(before != null && before.isAlive() ? before : minecraft.player);
        }
        viewpoint = null;
        before = null;
    }

    // The viewpoint is held only while the assistant's windows are up and the change it frames still waits.
    static void tick(boolean windowsUp) {
        if (viewpoint != null && (!windowsUp || focused == null || Minecraft.getInstance().level != viewpoint.level())) {
            release();
        }
    }

    private static float[] facing(Vec3 from, Vec3 to) {
        Vec3 along = to.subtract(from);
        double flat = Math.sqrt(along.x * along.x + along.z * along.z);
        return new float[] {(float) Math.toDegrees(Math.atan2(-along.x, along.z)),
                (float) -Math.toDegrees(Math.atan2(along.y, flat))};
    }

    private static boolean inSight(ClientLevel level, Entity by, Vec3 from, AABB box) {
        var hit = level.clip(new ClipContext(from, box.getCenter(), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                by));
        return hit.getType() == HitResult.Type.MISS || box.inflate(0.6).contains(hit.getLocation());
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || shown.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        List<Runnable> fills = new ArrayList<>();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (ConfirmationQueue.Change change : shown) {
            Optional<JsonObject> place = change.place().filter(where -> here(level, where));
            Optional<AABB> region = region(level, change);
            if (place.isEmpty() || region.isEmpty()) {
                continue;
            }
            float alpha = change == focused ? pulse() : 0.9f;
            if (place.get().has("extent")) {
                extent(poseStack, lines, fills, buffers, place.get().getAsJsonObject("extent"), alpha);
                continue;
            }
            List<Vec3> points = points(place.get());
            if (points.size() > 1) {
                for (int index = 1; index < points.size(); index++) {
                    Vec3 from = lifted(points.get(index - 1));
                    Vec3 to = lifted(points.get(index));
                    segment(poseStack, lines, from, to, alpha);
                    fills.add(() -> dotted(poseStack, buffers.getBuffer(RenderType.debugFilledBox()), from, to,
                            alpha));
                }
                for (Vec3 point : points) {
                    LevelRenderer.renderLineBox(poseStack, lines, cell(point).deflate(0.25), INK[0], INK[1], INK[2],
                            alpha);
                }
            } else {
                LevelRenderer.renderLineBox(poseStack, lines, region.get().inflate(0.04), INK[0], INK[1], INK[2],
                        alpha);
            }
        }
        buffers.endBatch(RenderType.lines());
        fills.forEach(Runnable::run);
        buffers.endBatch(RenderType.debugFilledBox());
        poseStack.popPose();
    }

    // A facility's extent changing: the old outline faint, the new one in clay, what it gains and gives up tinted.
    private static void extent(PoseStack poseStack, VertexConsumer lines, List<Runnable> fills,
            MultiBufferSource buffers, JsonObject extent, float alpha) {
        List<AABB> before = boxes(extent, "before");
        List<AABB> after = boxes(extent, "after");
        if (before.equals(after)) {
            for (AABB box : after) {
                LevelRenderer.renderLineBox(poseStack, lines, box.inflate(0.04), INK[0], INK[1], INK[2], alpha);
            }
            return;
        }
        for (AABB box : before) {
            LevelRenderer.renderLineBox(poseStack, lines, box.inflate(0.02), STONE[0], STONE[1], STONE[2],
                    alpha * 0.5f);
        }
        for (AABB box : after) {
            LevelRenderer.renderLineBox(poseStack, lines, box.inflate(0.06), INK[0], INK[1], INK[2], alpha);
        }
        // The tint stands just proud of the cells, so it shades the blocks there rather than hiding inside them.
        List<AABB> gained = without(after, before);
        List<AABB> lost = without(before, after);
        fills.add(() -> {
            VertexConsumer fill = buffers.getBuffer(RenderType.debugFilledBox());
            for (AABB box : gained) {
                tint(poseStack, fill, box.inflate(0.03), INK, alpha * 0.2f);
            }
            for (AABB box : lost) {
                tint(poseStack, fill, box.inflate(0.03), STONE, alpha * 0.25f);
            }
        });
    }

    private static void tint(PoseStack poseStack, VertexConsumer fill, AABB box, float[] color, float alpha) {
        LevelRenderer.addChainedFilledBoxVertices(poseStack, fill, box.minX, box.minY, box.minZ, box.maxX, box.maxY,
                box.maxZ, color[0], color[1], color[2], alpha);
    }

    private static List<AABB> boxes(JsonObject extent, String side) {
        List<AABB> boxes = new ArrayList<>();
        if (extent.has(side)) {
            for (var element : extent.getAsJsonArray(side)) {
                JsonArray row = element.getAsJsonArray();
                boxes.add(new AABB(row.get(0).getAsDouble(), row.get(1).getAsDouble(), row.get(2).getAsDouble(),
                        row.get(3).getAsDouble(), row.get(4).getAsDouble(), row.get(5).getAsDouble()));
            }
        }
        return boxes;
    }

    // What of the first boxes lies outside every one of the others, as boxes.
    private static List<AABB> without(List<AABB> boxes, List<AABB> cut) {
        List<AABB> left = new ArrayList<>(boxes);
        for (AABB knife : cut) {
            List<AABB> next = new ArrayList<>();
            for (AABB box : left) {
                next.addAll(minus(box, knife));
            }
            if (next.size() > MAX_PIECES) {
                return List.of();
            }
            left = next;
        }
        return left;
    }

    // A box less another: at most six slabs, cut along x, then y, then z.
    private static List<AABB> minus(AABB box, AABB knife) {
        if (!box.intersects(knife)) {
            return List.of(box);
        }
        List<AABB> pieces = new ArrayList<>();
        double minX = box.minX;
        double maxX = box.maxX;
        double minY = box.minY;
        double maxY = box.maxY;
        if (knife.minX > minX) {
            pieces.add(new AABB(minX, minY, box.minZ, knife.minX, maxY, box.maxZ));
            minX = knife.minX;
        }
        if (knife.maxX < maxX) {
            pieces.add(new AABB(knife.maxX, minY, box.minZ, maxX, maxY, box.maxZ));
            maxX = knife.maxX;
        }
        if (knife.minY > minY) {
            pieces.add(new AABB(minX, minY, box.minZ, maxX, knife.minY, box.maxZ));
            minY = knife.minY;
        }
        if (knife.maxY < maxY) {
            pieces.add(new AABB(minX, knife.maxY, box.minZ, maxX, maxY, box.maxZ));
            maxY = knife.maxY;
        }
        if (knife.minZ > box.minZ) {
            pieces.add(new AABB(minX, minY, box.minZ, maxX, maxY, knife.minZ));
        }
        if (knife.maxZ < box.maxZ) {
            pieces.add(new AABB(minX, minY, knife.maxZ, maxX, maxY, box.maxZ));
        }
        return pieces;
    }

    // A line alone is a hair at any distance; small blocks along it every half block give it body.
    private static void dotted(PoseStack poseStack, VertexConsumer fills, Vec3 from, Vec3 to, float alpha) {
        Vec3 along = to.subtract(from);
        int steps = Math.max(1, (int) Math.ceil(along.length() * 2));
        for (int step = 0; step <= steps; step++) {
            Vec3 at = from.add(along.scale(step / (double) steps));
            LevelRenderer.addChainedFilledBoxVertices(poseStack, fills, at.x - 0.09, at.y - 0.09, at.z - 0.09,
                    at.x + 0.09, at.y + 0.09, at.z + 0.09, INK[0], INK[1], INK[2], alpha * 0.8f);
        }
    }

    /** The first line of a change's words: enough to name it in the world or in the transcript. */
    static Component headline(Component brief) {
        String text = brief.getString();
        int end = text.indexOf('\n');
        return end < 0 ? brief : Component.literal(text.substring(0, end));
    }

    private static float pulse() {
        long since = System.currentTimeMillis() - focusedAt;
        if (since > 3000) {
            return 0.9f;
        }
        return 0.55f + 0.45f * (float) Math.abs(Math.cos(since / 250.0));
    }

    private static boolean here(ClientLevel level, JsonObject place) {
        return place.has("dimension") && place.get("dimension").getAsString()
                .equals(level.dimension().location().toString());
    }

    private static Optional<AABB> region(ClientLevel level, ConfirmationQueue.Change change) {
        return change.place().filter(place -> here(level, place)).flatMap(place -> {
            if (place.has("entity")) {
                UUID id = UUID.fromString(place.get("entity").getAsString());
                for (Entity entity : level.entitiesForRendering()) {
                    if (entity.getUUID().equals(id)) {
                        return Optional.of(entity.getBoundingBox());
                    }
                }
                return Optional.empty();
            }
            if (place.has("extent")) {
                JsonObject extent = place.getAsJsonObject("extent");
                AABB around = null;
                for (String side : List.of("before", "after")) {
                    for (AABB box : boxes(extent, side)) {
                        around = around == null ? box : around.minmax(box);
                    }
                }
                return Optional.ofNullable(around);
            }
            if (place.has("box")) {
                JsonArray corners = place.getAsJsonArray("box");
                Vec3 from = vector(corners.get(0).getAsJsonArray());
                Vec3 to = vector(corners.get(1).getAsJsonArray());
                return Optional.of(new AABB(from, to.add(1, 1, 1)));
            }
            List<Vec3> points = points(place);
            if (points.isEmpty()) {
                return Optional.empty();
            }
            AABB around = cell(points.getFirst());
            for (Vec3 point : points) {
                around = around.minmax(cell(point));
            }
            return Optional.of(around);
        });
    }

    private static List<Vec3> points(JsonObject place) {
        List<Vec3> points = new ArrayList<>();
        if (place.has("points")) {
            place.getAsJsonArray("points").forEach(point -> points.add(vector(point.getAsJsonArray())));
        }
        return points;
    }

    private static Vec3 vector(JsonArray cell) {
        return new Vec3(cell.get(0).getAsInt(), cell.get(1).getAsInt(), cell.get(2).getAsInt());
    }

    private static AABB cell(Vec3 corner) {
        return new AABB(corner, corner.add(1, 1, 1));
    }

    private static Vec3 lifted(Vec3 corner) {
        return corner.add(0.5, 0.5, 0.5);
    }

    private static void segment(PoseStack poseStack, VertexConsumer lines, Vec3 from, Vec3 to, float alpha) {
        Vec3 along = to.subtract(from);
        float length = (float) along.length();
        if (length == 0f) {
            return;
        }
        PoseStack.Pose pose = poseStack.last();
        for (Vec3 end : List.of(from, to)) {
            lines.addVertex(pose, (float) end.x, (float) end.y, (float) end.z)
                    .setColor(INK[0], INK[1], INK[2], alpha)
                    .setNormal(pose, (float) along.x / length, (float) along.y / length, (float) along.z / length);
        }
    }
}
