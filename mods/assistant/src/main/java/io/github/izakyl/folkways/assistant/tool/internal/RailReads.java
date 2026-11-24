package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.front.api.read.Railways;
import io.github.izakyl.folkways.core.script.api.Workers;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class RailReads {

    private static final String UUID_PART = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private RailReads() {
    }

    static void register(ToolRegistry registry) {
        register(registry, Railways.RAIL);
    }

    public static void register(ToolRegistry registry, ResourceLocation provider) {
        JsonObject stations = ActionTools.fields("limit:integer", "cursor:string");
        registry.register(ToolRegistry.spec("rail_stations", "List loaded Create stations in your read scope or "
                + "served by a claimed colony train's schedule (including wildcard destinations). "
                + "Out-of-scope served stations expose only station metadata, never track geometry. "
                + "limit defaults to 64 (1..64); pass next_cursor to continue; None means the last page. "
                + "The list is read again each call. Returns rail_unavailable without the rail provider.", stations),
                (context, args) -> stations(context, args, provider));
        JsonObject tracks = ActionTools.fields("limit:integer");
        tracks.add("at", SurveyReads.position(true));
        tracks.add("to", SurveyReads.position(true));
        registry.register(ToolRegistry.spec("rail_tracks", "Read loaded rail geometry between inclusive corners "
                + "in one dimension, each axis at most 1024 blocks. Clip edges to the query and read scope; "
                + "never follow connections outside it. limit defaults to 256 (1..256), per result list. "
                + "Edges are visible fragments, length is visible length; curve samples every 2 blocks, "
                + "at most 64 per original edge. "
                + "limited also marks omitted curve samples. Ends are genuine degree-one nodes, not cut boundaries. "
                + "direction is an outward unit vector; axis-aligned or equal-magnitude XZ means railway axis/45°. "
                + "path_point is the ground cell immediately beyond the last rail in the outward direction, "
                + "or None outside the loaded scope.", tracks, "at", "to"),
                (context, args) -> tracks(context, args, provider));
    }

    private static ToolReply unavailable() {
        ToolText text = new ToolText();
        JsonObject data = ToolReply.error("rail_unavailable").data();
        data.add("refusal", text.text(Component.translatable("folkways.assistant.rail_unavailable")));
        return text.reply(data);
    }

    private static int limit(JsonObject args, int max) {
        int limit = args.has("limit") ? args.get("limit").getAsBigDecimal().intValueExact() : max;
        if (limit < 1 || limit > max) {
            throw new ActionRegistry.Refused("bounds_limit");
        }
        return limit;
    }

    private static ToolReply stations(ToolContext context, JsonObject args, ResourceLocation provider) {
        try {
            int limit = limit(args, 64);
            String cursor = args.has("cursor") ? args.get("cursor").getAsString() : null;
            if (cursor != null && !cursor.matches(UUID_PART + "/" + UUID_PART)) {
                return ToolReply.error("invalid_cursor");
            }
            var source = Railways.source(provider);
            if (source.isEmpty()) {
                return unavailable();
            }
            ReadScope scope = new ReadScope(context);
            JsonArray stations = new JsonArray();
            String next = null;
            String last = null;
            var server = context.player().getServer();
            for (var station : source.get().stations(server, context.colony())) {
                Workers.checkTime();
                String key = station.graph() + "/" + station.id();
                if (cursor != null && key.compareTo(cursor) <= 0) {
                    continue;
                }
                ServerLevel level = server.getLevel(ResourceKey.create(
                        Registries.DIMENSION, station.dimension()));
                if (level == null || !loaded(level, station.at())
                        || !station.servedByColonyTrain() && !scope.contains(level, station.at())) {
                    continue;
                }
                if (stations.size() == limit) {
                    next = last;
                    break;
                }
                JsonObject row = new JsonObject();
                row.addProperty("id", station.id().toString());
                row.addProperty("name", station.name());
                row.add("at", position(station.at().getX(), station.at().getY(), station.at().getZ(), level));
                row.addProperty("graph", station.graph().toString());
                row.addProperty("served_by_colony_train", station.servedByColonyTrain());
                stations.add(row);
                last = key;
            }
            JsonObject data = new JsonObject();
            data.add("stations", stations);
            data.add("next_cursor", next == null ? JsonNull.INSTANCE : new JsonPrimitive(next));
            return new ToolReply(data, List.of());
        } catch (ActionRegistry.Refused refusal) {
            return ToolReply.error(refusal.getMessage());
        } catch (ArithmeticException invalid) {
            return ToolReply.error("invalid_arguments");
        }
    }

    private static ToolReply tracks(ToolContext context, JsonObject args, ResourceLocation provider) {
        try {
            int limit = limit(args, 256);
            JsonObject at = args.getAsJsonObject("at");
            JsonObject to = args.getAsJsonObject("to");
            if (!at.get("dimension").equals(to.get("dimension"))) {
                return ToolReply.error("mixed_dimensions");
            }
            ServerLevel level = SurveyReads.level(context, at.get("dimension").getAsString());
            BlockPos from = SurveyReads.pos(at);
            BlockPos end = SurveyReads.pos(to);
            if (!level.isInWorldBounds(from) || !level.isInWorldBounds(end)) {
                return ToolReply.error("outside_world");
            }
            if (Math.abs((long) from.getX() - end.getX()) >= 1024
                    || Math.abs((long) from.getY() - end.getY()) >= 1024
                    || Math.abs((long) from.getZ() - end.getZ()) >= 1024) {
                return ToolReply.error("bounds_limit");
            }
            var source = Railways.source(provider);
            if (source.isEmpty()) {
                return unavailable();
            }
            AABB bounds = new AABB(Vec3.atLowerCornerOf(from), Vec3.atLowerCornerOf(end)).expandTowards(1, 1, 1);
            ReadScope scope = new ReadScope(context);
            Predicate<Vec3> visible = point -> bounds.contains(point) && loaded(level, BlockPos.containing(point))
                    && scope.contains(level, BlockPos.containing(point));
            var raw = source.get().tracks(level, context.colony(), bounds);
            JsonArray nodes = new JsonArray();
            JsonArray edges = new JsonArray();
            JsonArray ends = new JsonArray();
            boolean limited = false;
            for (var node : raw.nodes()) {
                Workers.checkTime();
                if (!visible.test(node.at())) {
                    continue;
                }
                if (nodes.size() == limit) {
                    limited = true;
                    continue;
                }
                JsonObject row = new JsonObject();
                row.addProperty("id", node.id());
                row.addProperty("graph", node.graph().toString());
                row.add("at", position(node.at(), level));
                nodes.add(row);
                if (node.outward().isPresent()) {
                    JsonObject tip = row.deepCopy();
                    Vec3 direction = node.outward().get().normalize();
                    tip.add("direction", vector(direction));
                    BlockPos path = BlockPos.containing(node.at().add(direction.scale(0.5))).below();
                    tip.add("path_point", visible.test(Vec3.atCenterOf(path))
                            && visible.test(Vec3.atCenterOf(path.above()))
                            ? position(path.getX(), path.getY(), path.getZ(), level) : JsonNull.INSTANCE);
                    ends.add(tip);
                }
            }
            for (var edge : raw.edges()) {
                int remaining = 64;
                for (var fragment : clip(edge.points(), bounds, visible)) {
                    if (edges.size() == limit) {
                        limited = true;
                        break;
                    }
                    JsonObject row = new JsonObject();
                    row.addProperty("id", edge.id());
                    row.addProperty("graph", edge.graph().toString());
                    row.add("from", position(fragment.getFirst(), level));
                    row.add("to", position(fragment.getLast(), level));
                    row.addProperty("curve", edge.curve());
                    double length = 0;
                    for (int index = 1; index < fragment.size(); index++) {
                        length += fragment.get(index).distanceTo(fragment.get(index - 1));
                    }
                    row.addProperty("length", length);
                    JsonArray samples = new JsonArray();
                    if (edge.curve()) {
                        double distance = 0;
                        double next = 0;
                        for (int index = 1; index < fragment.size(); index++) {
                            Vec3 start = fragment.get(index - 1);
                            Vec3 finish = fragment.get(index);
                            double span = start.distanceTo(finish);
                            if (span < 1e-12) {
                                continue;
                            }
                            while (next <= distance + span && samples.size() < remaining) {
                                samples.add(position(start.lerp(finish, (next - distance) / span), level));
                                next += 2;
                            }
                            distance += span;
                        }
                        limited |= next <= length;
                        remaining -= samples.size();
                    }
                    row.add("samples", samples);
                    edges.add(row);
                }
            }
            JsonObject data = new JsonObject();
            data.add("nodes", nodes);
            data.add("edges", edges);
            data.add("ends", ends);
            data.addProperty("limited", limited);
            return new ToolReply(data, List.of());
        } catch (ActionRegistry.Refused refusal) {
            return ToolReply.error(refusal.getMessage());
        } catch (ArithmeticException invalid) {
            return ToolReply.error("invalid_arguments");
        }
    }

    private static List<List<Vec3>> clip(List<Vec3> points, AABB bounds, Predicate<Vec3> visible) {
        List<List<Vec3>> fragments = new ArrayList<>();
        List<Vec3> current = new ArrayList<>();
        for (int index = 1; index < points.size(); index++) {
            Vec3 start = points.get(index - 1);
            Vec3 finish = points.get(index);
            double[] range = {0, 1};
            double[] a = {start.x, start.y, start.z};
            double[] b = {finish.x, finish.y, finish.z};
            double[] low = {bounds.minX, bounds.minY, bounds.minZ};
            double[] high = {bounds.maxX, bounds.maxY, bounds.maxZ};
            for (int axis = 0; axis < 3; axis++) {
                double delta = b[axis] - a[axis];
                if (delta == 0) {
                    if (a[axis] < low[axis] || a[axis] >= high[axis]) {
                        range[1] = -1;
                    }
                } else {
                    double first = (low[axis] - a[axis]) / delta;
                    double last = (high[axis] - a[axis]) / delta;
                    range[0] = Math.max(range[0], Math.min(first, last));
                    range[1] = Math.min(range[1], Math.max(first, last));
                }
            }
            if (range[0] >= range[1]) {
                flush(fragments, current);
                continue;
            }
            List<Double> cuts = new ArrayList<>(List.of(range[0], range[1]));
            for (int axis = 0; axis < 3; axis++) {
                if (a[axis] == b[axis]) {
                    continue;
                }
                double first = a[axis] + (b[axis] - a[axis]) * range[0];
                double last = a[axis] + (b[axis] - a[axis]) * range[1];
                for (double plane = Math.floor(Math.min(first, last)) + 1; plane < Math.max(first, last); plane++) {
                    cuts.add((plane - a[axis]) / (b[axis] - a[axis]));
                }
            }
            cuts.sort(Double::compare);
            for (int cut = 1; cut < cuts.size(); cut++) {
                Workers.checkTime();
                double first = cuts.get(cut - 1);
                double last = cuts.get(cut);
                if (last - first < 1e-12) {
                    continue;
                }
                Vec3 middle = start.lerp(finish, (first + last) / 2);
                if (!visible.test(middle)) {
                    flush(fragments, current);
                    continue;
                }
                // Keep boundary coordinates inside the authorized voxel, including negative travel directions.
                Vec3 p = start.lerp(finish, first);
                Vec3 q = start.lerp(finish, last);
                if (!visible.test(p)) {
                    p = start.lerp(finish, first + (last - first) * 1e-7);
                }
                if (!visible.test(q)) {
                    q = start.lerp(finish, last - (last - first) * 1e-7);
                }
                if (!visible.test(p)) {
                    p = middle;
                }
                if (!visible.test(q)) {
                    q = middle;
                }
                if (!current.isEmpty() && current.getLast().distanceToSqr(p) > 1e-10) {
                    flush(fragments, current);
                }
                current.add(p);
                current.add(q);
            }
        }
        flush(fragments, current);
        return List.copyOf(fragments);
    }

    private static void flush(List<List<Vec3>> fragments, List<Vec3> current) {
        if (!current.isEmpty()) {
            fragments.add(List.copyOf(current));
            current.clear();
        }
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos)
                && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    private static JsonObject position(Vec3 point, ServerLevel level) {
        JsonObject at = vector(point);
        at.addProperty("dimension", level.dimension().location().toString());
        return at;
    }

    private static JsonObject position(int x, int y, int z, ServerLevel level) {
        JsonObject at = new JsonObject();
        at.addProperty("x", x);
        at.addProperty("y", y);
        at.addProperty("z", z);
        at.addProperty("dimension", level.dimension().location().toString());
        return at;
    }

    private static JsonObject vector(Vec3 point) {
        JsonObject at = new JsonObject();
        at.addProperty("x", point.x);
        at.addProperty("y", point.y);
        at.addProperty("z", point.z);
        return at;
    }
}
