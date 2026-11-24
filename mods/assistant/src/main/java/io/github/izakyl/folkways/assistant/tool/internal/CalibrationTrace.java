package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.net.ToolNetwork;
import io.github.izakyl.folkways.assistant.sandbox.Probe;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the calibration reads into marks the asking player sees in the world: the parts {@code mechanism} found, its
 * controls apart, and for {@code probe} the control it tried and what then moved in the sandbox. The sandbox copy
 * stands at the machine's own coordinates, so what moved there is drawn over the real machine. Marks carry only
 * positions and motion; they are pictures, never words.
 */
final class CalibrationTrace {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalibrationTrace.class);
    private static final int MAX_NODES = 64;
    private static final int MAX_CELLS = 96;

    private CalibrationTrace() {
    }

    /** A mark is only a picture of the read: whatever goes wrong drawing it never fails the read. */
    static void safely(Runnable mark) {
        try {
            mark.run();
        } catch (RuntimeException failed) {
            LOGGER.debug("Calibration mark skipped", failed);
        }
    }

    static void mechanism(ServerPlayer player, MechanismScan scan, MechanismGraph graph) {
        ServerLevel level = scan.level();
        JsonObject mark = new JsonObject();
        mark.addProperty("kind", "mechanism");
        mark.addProperty("dimension", level.dimension().location().toString());
        JsonArray controls = new JsonArray();
        JsonArray parts = new JsonArray();
        for (MechanismGraph.Node node : graph.nodes()) {
            if (controls.size() + parts.size() >= MAX_NODES) {
                break;
            }
            centre(level, node.at()).map(CalibrationTrace::vector).ifPresent(at ->
                    (node.roles().contains(MechanismGraph.Role.CONTROL) ? controls : parts).add(at));
        }
        mark.add("controls", controls);
        mark.add("parts", parts);
        ToolNetwork.mark(player, mark.toString());
    }

    static void probeStarted(ServerPlayer player, UUID id, Probe.Request request) {
        ToolNetwork.mark(player, probe(id, request, "start").toString());
    }

    static void probed(ServerPlayer player, UUID id, Probe.Request request, JsonObject data) {
        JsonObject mark = probe(id, request, "done");
        if (data.has("error")) {
            ToolNetwork.mark(player, mark.toString());
            return;
        }
        ServerLevel level = request.scan().level();
        // Only a turned aim is drawn: a barrel swinging from its old line of fire to its new one.
        JsonArray aims = new JsonArray();
        for (JsonElement element : rows(data, "changes")) {
            JsonObject change = element.getAsJsonObject();
            if (!change.has("before") || !change.has("after") || aims.size() >= 4) {
                continue;
            }
            JsonObject before = change.getAsJsonObject("before");
            JsonObject after = change.getAsJsonObject("after");
            if (!aimed(before) && !aimed(after)) {
                continue;
            }
            labelled(level, request, change.get("id").getAsString()).ifPresent(at -> {
                double yaw0 = number(before, "yaw", number(after, "yaw", 0));
                double pitch0 = number(before, "pitch", number(after, "pitch", 0));
                JsonObject row = new JsonObject();
                row.add("at", vector(at));
                row.add("from", pair(yaw0, pitch0));
                row.add("to", pair(number(after, "yaw", yaw0), number(after, "pitch", pitch0)));
                aims.add(row);
            });
        }
        mark.add("aims", aims);
        JsonArray moved = new JsonArray();
        for (JsonElement element : rows(data, "entities")) {
            JsonObject entity = element.getAsJsonObject();
            if (entity.has("moved") && moved.size() < 4) {
                moved.add(entity.get("moved"));
            }
        }
        mark.add("moved", moved);
        JsonArray structures = new JsonArray();
        for (JsonElement element : rows(data, "structures")) {
            JsonObject structure = element.getAsJsonObject();
            if (structure.has("brought_along") || !structure.has("moved") || !structure.has("at")
                    || structures.size() >= 4) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.add("moved", structure.get("moved"));
            row.add("at", structure.get("at"));
            structures.add(row);
        }
        mark.add("structures", structures);
        JsonObject blocks = data.has("blocks") ? data.getAsJsonObject("blocks") : new JsonObject();
        mark.add("added", cells(level, request, blocks, "added"));
        mark.add("removed", cells(level, request, blocks, "removed"));
        mark.add("altered", cells(level, request, blocks, "altered"));
        ToolNetwork.mark(player, mark.toString());
    }

    private static JsonObject probe(UUID id, Probe.Request request, String phase) {
        ServerLevel level = request.scan().level();
        JsonObject mark = new JsonObject();
        mark.addProperty("kind", "probe");
        mark.addProperty("phase", phase);
        mark.addProperty("id", id.toString());
        mark.addProperty("dimension", level.dimension().location().toString());
        WorldPos control = switch (request.input()) {
            case Probe.Input.Write write -> write.at();
            case Probe.Input.Redstone redstone -> redstone.at();
            case Probe.Input.Routine routine -> routine.facility().anchor();
        };
        at(level, control).ifPresent(at -> mark.add("control", at));
        return mark;
    }

    private static boolean aimed(JsonObject state) {
        return state.has("yaw") || state.has("pitch");
    }

    private static double number(JsonObject object, String key, double otherwise) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isNumber()
                ? object.get(key).getAsDouble() : otherwise;
    }

    private static JsonArray pair(double yaw, double pitch) {
        JsonArray row = new JsonArray();
        row.add(Math.round(yaw * 10) / 10.0);
        row.add(Math.round(pitch * 10) / 10.0);
        return row;
    }

    private static JsonArray rows(JsonObject data, String list) {
        return data.has(list) && data.get(list).isJsonArray() ? data.getAsJsonArray(list) : new JsonArray();
    }

    // Cells as the probe prints them: [x, y, z] in the scanned dimension, or a structure's local cell.
    private static JsonArray cells(ServerLevel level, Probe.Request request, JsonObject blocks, String list) {
        JsonArray out = new JsonArray();
        for (JsonElement element : rows(blocks, list)) {
            if (out.size() >= MAX_CELLS) {
                break;
            }
            JsonObject row = element.getAsJsonObject();
            JsonElement at = row.get("at");
            Optional<Vec3> world;
            if (at.isJsonArray()) {
                world = Optional.of(Vec3.atCenterOf(cell(at.getAsJsonArray())));
            } else {
                JsonObject local = at.getAsJsonObject();
                world = centre(level, new WorldPos(new Realm.Frame(UUID.fromString(
                        local.get("structure").getAsString())), cell(local.getAsJsonArray("cell"))));
            }
            world.ifPresent(position -> out.add(vector(position)));
        }
        return out;
    }

    // A change is named "block@x,y,z", with "~structure" (its first eight characters) for a structure's local cell.
    private static Optional<Vec3> labelled(ServerLevel level, Probe.Request request, String id) {
        int sign = id.indexOf('@');
        if (sign < 0) {
            return Optional.empty();
        }
        String rest = id.substring(sign + 1);
        String structure = null;
        int tilde = rest.indexOf('~');
        if (tilde >= 0) {
            structure = rest.substring(tilde + 1);
            rest = rest.substring(0, tilde);
        }
        String[] parts = rest.split(",");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            BlockPos cell = new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
            if (structure == null) {
                return request.scan().realm() instanceof Realm.Frame frame
                        ? centre(level, new WorldPos(frame, cell))
                        : Optional.of(Vec3.atCenterOf(cell));
            }
            String prefix = structure;
            for (WorldSpaces.Frame frame : WorldSpaces.frames(level)) {
                if (frame.id().toString().startsWith(prefix)) {
                    return centre(level, new WorldPos(new Realm.Frame(frame.id()), cell));
                }
            }
            return Optional.empty();
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<JsonArray> at(ServerLevel level, WorldPos pos) {
        return centre(level, pos).map(CalibrationTrace::vector);
    }

    private static Optional<Vec3> centre(ServerLevel level, WorldPos pos) {
        return WorldSpaces.world(level, pos).map(bottom -> bottom.add(0, 0.5, 0));
    }

    private static BlockPos cell(JsonArray xyz) {
        return new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
    }

    private static JsonArray vector(Vec3 at) {
        JsonArray row = new JsonArray();
        for (double value : List.of(at.x, at.y, at.z)) {
            row.add(Math.round(value * 100) / 100.0);
        }
        return row;
    }
}
