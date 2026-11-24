package io.github.izakyl.folkways.assistant.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.assistant.net.CalibrationMark;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Shows, where the machine stands, which parts the assistant is looking at while it calibrates. A mechanism read
 * outlines the parts it found, its controls in clay; a probe rings the control it tries, and once the sandbox has
 * answered plays the trial back over the real machine: the blocks that moved slide to where they went, a cannon's
 * barrel swings from its old aim to its new one, a walker's walk is drawn on the ground. The marks carry no words.
 */
@EventBusSubscriber(modid = AssistantMod.ID, value = Dist.CLIENT)
public final class CalibrationMarks {

    private static final float[] CLAY = {0xD9 / 255f, 0x77 / 255f, 0x57 / 255f};
    private static final float[] CREAM = {0xEC / 255f, 0xEB / 255f, 0xE6 / 255f};
    private static final float[] STONE = {0xB8 / 255f, 0xB3 / 255f, 0xA7 / 255f};
    private static final long GRAPH_LIFE = 9_000;
    private static final long PROBE_LIFE = 8_000;
    private static final long PROBE_WAIT = 20_000;
    private static final long STAGGER = 90;
    private static final long FADE = 900;

    private static final List<Mark> MARKS = new ArrayList<>();

    private CalibrationMarks() {
    }

    private static final class Mark {
        final JsonObject data;
        final long shownAt;
        JsonObject result;
        long answeredAt;

        Mark(JsonObject data, long shownAt) {
            this.data = data;
            this.shownAt = shownAt;
        }

        boolean probe() {
            return data.get("kind").getAsString().equals("probe");
        }

        long endsAt() {
            if (!probe()) {
                return shownAt + GRAPH_LIFE;
            }
            return result == null ? shownAt + PROBE_WAIT : answeredAt + PROBE_LIFE;
        }

        float alpha(long now) {
            float in = Mth.clamp((now - shownAt) / 300f, 0, 1);
            float out = Mth.clamp((endsAt() - now) / (float) FADE, 0, 1);
            return Math.min(in, out);
        }
    }

    static void receive(CalibrationMark payload) {
        JsonObject data;
        try {
            data = JsonParser.parseString(payload.json()).getAsJsonObject();
        } catch (RuntimeException invalid) {
            return;
        }
        long now = System.currentTimeMillis();
        if (data.get("kind").getAsString().equals("probe") && data.get("phase").getAsString().equals("done")) {
            for (Mark mark : MARKS) {
                if (mark.probe() && mark.result == null && mark.data.get("id").equals(data.get("id"))) {
                    mark.result = data;
                    mark.answeredAt = now;
                    return;
                }
            }
            Mark late = new Mark(data, now);
            late.result = data;
            late.answeredAt = now;
            MARKS.add(late);
            return;
        }
        if (data.get("kind").getAsString().equals("mechanism")) {
            MARKS.removeIf(mark -> !mark.probe());
        }
        MARKS.add(new Mark(data, now));
        while (MARKS.size() > 6) {
            MARKS.removeFirst();
        }
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || MARKS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        MARKS.removeIf(mark -> mark.endsAt() <= now);
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || MARKS.isEmpty()) {
            return;
        }
        String dimension = level.dimension().location().toString();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Scene scene = new Scene(poseStack, buffers.getBuffer(RenderType.lines()));
        for (Mark mark : MARKS) {
            if (!mark.data.has("dimension") || !mark.data.get("dimension").getAsString().equals(dimension)) {
                continue;
            }
            float alpha = mark.alpha(now);
            if (mark.probe()) {
                probe(level, scene, mark, now, alpha);
            } else {
                graph(scene, mark, now, alpha);
            }
        }
        buffers.endBatch(RenderType.lines());
        VertexConsumer fills = buffers.getBuffer(RenderType.debugFilledBox());
        scene.fills.forEach(fill -> fill.accept(fills));
        buffers.endBatch(RenderType.debugFilledBox());
        poseStack.popPose();
    }

    private static final class Scene {
        final PoseStack poseStack;
        final VertexConsumer lines;
        final List<java.util.function.Consumer<VertexConsumer>> fills = new ArrayList<>();

        Scene(PoseStack poseStack, VertexConsumer lines) {
            this.poseStack = poseStack;
            this.lines = lines;
        }

        void outline(AABB box, float[] color, float alpha) {
            LevelRenderer.renderLineBox(poseStack, lines, box, color[0], color[1], color[2], alpha);
        }

        void fill(AABB box, float[] color, float alpha) {
            fills.add(consumer -> LevelRenderer.addChainedFilledBoxVertices(poseStack, consumer, box.minX, box.minY,
                    box.minZ, box.maxX, box.maxY, box.maxZ, color[0], color[1], color[2], alpha));
        }

        void dot(Vec3 at, double size, float[] color, float alpha) {
            fill(new AABB(at, at).inflate(size), color, alpha);
        }

        void line(Vec3 from, Vec3 to, float[] color, float alpha) {
            Vec3 along = to.subtract(from);
            float length = (float) along.length();
            if (length == 0f) {
                return;
            }
            PoseStack.Pose pose = poseStack.last();
            for (Vec3 end : List.of(from, to)) {
                lines.addVertex(pose, (float) end.x, (float) end.y, (float) end.z)
                        .setColor(color[0], color[1], color[2], alpha)
                        .setNormal(pose, (float) along.x / length, (float) along.y / length,
                                (float) along.z / length);
            }
        }
    }

    // --- mechanism ---------------------------------------------------------------------------------------------

    // The parts light up one after another, controls first: the controls in clay, everything else a faint cream.
    private static void graph(Scene scene, Mark mark, long now, float alpha) {
        JsonArray controls = rows(mark.data, "controls");
        JsonArray parts = rows(mark.data, "parts");
        int step = 0;
        for (JsonArray list : List.of(controls, parts)) {
            boolean control = list == controls;
            for (JsonElement element : list) {
                float grow = Mth.clamp((now - mark.shownAt - step++ * STAGGER) / 260f, 0, 1);
                if (grow <= 0) {
                    continue;
                }
                AABB cell = cell(vector(element.getAsJsonArray()));
                double pop = (1 - grow) * 0.2;
                if (control) {
                    scene.outline(cell.inflate(0.04 + pop), CLAY, alpha * grow * 0.9f);
                    scene.fill(cell.inflate(0.02 + pop), CLAY, alpha * grow * 0.16f);
                } else {
                    scene.outline(cell.inflate(0.03 + pop), CREAM, alpha * grow * 0.4f);
                }
            }
        }
    }

    // --- probe -------------------------------------------------------------------------------------------------

    private static void probe(ClientLevel level, Scene scene, Mark mark, long now, float alpha) {
        Vec3 control = mark.data.has("control") ? vector(mark.data.getAsJsonArray("control")) : null;
        if (control != null) {
            double breathe = mark.result == null ? 0.08 + 0.08 * Math.abs(Math.sin((now - mark.shownAt) / 220.0))
                    : 0.06;
            AABB cell = cell(control);
            scene.outline(cell.inflate(breathe), CLAY, alpha);
            scene.fill(cell.inflate(0.02), CLAY, alpha * 0.18f);
            if (mark.result == null) {
                // Waiting on the sandbox: a bead circles the control.
                double turn = (now - mark.shownAt) / 450.0;
                scene.dot(control.add(Math.cos(turn) * 0.85, 0.55, Math.sin(turn) * 0.85), 0.09, CREAM, alpha);
            }
        }
        if (mark.result == null) {
            return;
        }
        JsonObject result = mark.result;
        double since = (now - mark.answeredAt) / 1000.0;
        // Three plays of what moved, eased, then it rests where it went.
        double play = since >= 4.5 ? 1 : ease((since % 1.5) / 1.2);
        // A cell can empty and fill again in one trial (a piston's own head moves in behind what it pushes).
        JsonArray altered = rows(result, "altered");
        JsonArray removed = rows(result, "removed").deepCopy();
        removed.addAll(altered);
        JsonArray added = rows(result, "added").deepCopy();
        added.addAll(altered);
        Vec3 by = shift(removed, added);
        if (by == null) {
            by = first(result, "moved");
        }
        if (by != null && by.lengthSqr() > 0.01 && !removed.isEmpty()) {
            Vec3 shift = by.scale(play);
            for (JsonElement element : removed) {
                AABB from = cell(vector(element.getAsJsonArray()));
                scene.outline(from.deflate(0.02), STONE, alpha * 0.55f);
                scene.fill(from.move(shift).deflate(0.04), CLAY, alpha * 0.28f);
                scene.outline(from.move(shift).deflate(0.04), CLAY, alpha);
            }
        } else {
            for (JsonElement element : added) {
                AABB at = cell(vector(element.getAsJsonArray()));
                scene.fill(at.deflate(0.04), CLAY, alpha * 0.24f * (float) play);
                scene.outline(at.deflate(0.04), CLAY, alpha * (float) play);
            }
            for (JsonElement element : removed) {
                scene.outline(cell(vector(element.getAsJsonArray())).deflate(0.02), STONE, alpha * 0.55f);
            }
        }
        for (JsonElement element : rows(result, "aims")) {
            JsonObject aim = element.getAsJsonObject();
            aim(scene, vector(aim.getAsJsonArray("at")), aim.getAsJsonArray("from"), aim.getAsJsonArray("to"), play,
                    alpha);
        }
        for (JsonElement element : rows(result, "structures")) {
            JsonObject structure = element.getAsJsonObject();
            walk(level, scene, vector(structure.getAsJsonArray("at")), vector(structure.getAsJsonArray("moved")),
                    play, alpha);
        }
    }

    // A barrel's swing: the old line of fire faint, the new one in clay, and the line between them sweeping over.
    private static void aim(Scene scene, Vec3 mount, JsonArray from, JsonArray to, double play, float alpha) {
        double yaw0 = from.get(0).getAsDouble();
        double pitch0 = from.get(1).getAsDouble();
        // The barrel turns the short way round: 270 to 0 is a quarter turn, not three.
        double yaw1 = yaw0 + Mth.wrapDegrees(to.get(0).getAsDouble() - yaw0);
        double pitch1 = to.get(1).getAsDouble();
        Vec3 bore = mount.add(0, 2, 0);
        double reach = 9;
        scene.line(bore, bore.add(direction(yaw0, pitch0).scale(reach)), STONE, alpha * 0.6f);
        double yaw = yaw0 + (yaw1 - yaw0) * play;
        double pitch = pitch0 + (pitch1 - pitch0) * play;
        Vec3 now = bore.add(direction(yaw, pitch).scale(reach));
        scene.line(bore, now, CLAY, alpha);
        for (int step = 1; step <= 12; step++) {
            scene.dot(bore.add(direction(yaw, pitch).scale(reach * step / 12.0)), 0.1, CLAY, alpha * 0.85f);
        }
        int arcs = 16;
        Vec3 last = null;
        for (int step = 0; step <= arcs; step++) {
            double t = play * step / arcs;
            Vec3 at = bore.add(direction(yaw0 + (yaw1 - yaw0) * t, pitch0 + (pitch1 - pitch0) * t).scale(reach * 0.6));
            if (last != null) {
                scene.line(last, at, CREAM, alpha * 0.7f);
            }
            last = at;
        }
        scene.dot(now, 0.14, CREAM, alpha);
    }

    // Big Cannons keeps yaw and pitch the way an entity does: yaw 0 looks south, positive pitch up.
    private static Vec3 direction(double yaw, double pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    // A walk: chevrons along the ground from where the structure stood to where it ended, a ring where it stops.
    private static void walk(ClientLevel level, Scene scene, Vec3 end, Vec3 moved, double play, float alpha) {
        Vec3 start = end.subtract(moved);
        Vec3 flat = new Vec3(moved.x, 0, moved.z);
        double length = flat.length();
        if (length < 0.3) {
            return;
        }
        double ground = groundBelow(level, start);
        Vec3 from = new Vec3(start.x, ground + 0.08, start.z);
        Vec3 heading = flat.normalize();
        Vec3 side = new Vec3(-heading.z, 0, heading.x);
        double reached = length * play;
        for (double at = 0.8; at < reached; at += 1.2) {
            Vec3 tip = from.add(heading.scale(at));
            scene.line(tip.subtract(heading.scale(0.45)).add(side.scale(0.45)), tip, CLAY, alpha);
            scene.line(tip.subtract(heading.scale(0.45)).subtract(side.scale(0.45)), tip, CLAY, alpha);
        }
        Vec3 stop = from.add(heading.scale(reached));
        int around = 24;
        for (int step = 0; step < around; step++) {
            double a = step * Math.PI * 2 / around;
            double b = (step + 1) * Math.PI * 2 / around;
            scene.line(stop.add(Math.cos(a) * 1.6, 0, Math.sin(a) * 1.6), stop.add(Math.cos(b) * 1.6, 0,
                    Math.sin(b) * 1.6), CREAM, alpha);
        }
        scene.dot(stop, 0.16, CLAY, alpha);
    }

    private static double groundBelow(ClientLevel level, Vec3 at) {
        BlockPos pos = BlockPos.containing(at);
        for (int down = 0; down < 32; down++) {
            BlockPos below = pos.below(down);
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                return below.getY() + 1;
            }
        }
        return at.y;
    }

    // --- values ------------------------------------------------------------------------------------------------

    private static double ease(double t) {
        double c = Mth.clamp(t, 0, 1);
        return c * c * (3 - 2 * c);
    }

    private static JsonArray rows(JsonObject data, String list) {
        return data.has(list) && data.get(list).isJsonArray() ? data.getAsJsonArray(list) : new JsonArray();
    }

    private static Vec3 first(JsonObject data, String list) {
        for (JsonElement element : rows(data, list)) {
            Vec3 at = vector(element.getAsJsonArray());
            if (at.lengthSqr() > 0.01) {
                return at;
            }
        }
        return null;
    }

    // Where the moved blocks went: the straight step that carries most of the cells that emptied onto cells that filled.
    private static Vec3 shift(JsonArray removed, JsonArray added) {
        if (removed.isEmpty() || added.isEmpty()) {
            return null;
        }
        java.util.Set<BlockPos> filled = new java.util.HashSet<>();
        added.forEach(cell -> filled.add(BlockPos.containing(vector(cell.getAsJsonArray()))));
        Vec3 best = null;
        int most = 0;
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            for (int step = 1; step <= 8; step++) {
                int hits = 0;
                for (JsonElement cell : removed) {
                    if (filled.contains(BlockPos.containing(vector(cell.getAsJsonArray())).relative(direction, step))) {
                        hits++;
                    }
                }
                if (hits > most) {
                    most = hits;
                    best = Vec3.atLowerCornerOf(direction.getNormal()).scale(step);
                }
            }
        }
        return most * 2 >= removed.size() ? best : null;
    }

    private static Vec3 vector(JsonArray xyz) {
        return new Vec3(xyz.get(0).getAsDouble(), xyz.get(1).getAsDouble(), xyz.get(2).getAsDouble());
    }

    // Positions are block centres: a cell is the block round one.
    private static AABB cell(Vec3 centre) {
        return new AABB(centre, centre).inflate(0.5);
    }
}
