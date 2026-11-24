package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyScopes;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

final class ReviewSubjects {

    private ReviewSubjects() {
    }

    static List<List<Integer>> group(Optional<ToolContext> context, List<ActionBatch.Intent> intents,
            List<ToolReply> previews) {
        int count = intents.size();
        var subjects = intents.stream().map(intent -> key(context, intent)).toList();
        int[] parents = new int[count];
        boolean[][] dependencies = new boolean[count][count];
        for (int index = 0; index < count; index++) {
            parents[index] = index;
        }
        for (int index = 0; index < count; index++) {
            for (int other = 0; other < count; other++) {
                if (index == other) {
                    continue;
                }
                dependencies[index][other] = dependsOn(intents.get(index), previews.get(index),
                        intents.get(other), previews.get(other));
                if (subjects.get(index).equals(subjects.get(other)) || dependencies[index][other]) {
                    parents[root(parents, other)] = root(parents, index);
                }
            }
        }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        boolean[] visited = new boolean[count];
        for (int index = 0; index < count; index++) {
            order(index, dependencies, visited, parents, groups);
        }
        return List.copyOf(groups.values());
    }

    private static int root(int[] parents, int index) {
        while (parents[index] != index) {
            index = parents[index];
        }
        return index;
    }

    private static void order(int index, boolean[][] dependencies, boolean[] visited, int[] parents,
            Map<Integer, List<Integer>> groups) {
        if (visited[index]) {
            return;
        }
        visited[index] = true;
        for (int other = 0; other < visited.length; other++) {
            if (dependencies[index][other]) {
                order(other, dependencies, visited, parents, groups);
            }
        }
        groups.computeIfAbsent(root(parents, index), ignored -> new ArrayList<>()).add(index);
    }

    private static String key(Optional<ToolContext> context, ActionBatch.Intent intent) {
        var args = intent.arguments();
        return switch (intent.action()) {
            case "rename_resident", "set_work" -> "resident/" + args.get("resident_id").getAsString();
            case "set_facility", "delete_facility" -> "facility/" + args.get("name").getAsString();
            case "run", "cancel" -> "facility/" + context.map(held ->
                    FacilityTools.definedName(held, args.get("facility").getAsString()))
                    .orElseGet(() -> args.get("facility").getAsString());
            case "set_facility_parameter" -> context.flatMap(held ->
                    FacilityTools.find(held, args.get("id").getAsString()))
                    .filter(ColonyScopes.Scope.class::isInstance).isPresent() ? "settings"
                            : "facility/" + args.get("id").getAsString();
            case "erase_zone_or_path" -> "facility/" + args.get("id").getAsString();
            case "set_setting" -> args.has("zone_or_path") ? "facility/" + args.get("zone_or_path").getAsString()
                    : "settings";
            case "set_work_default", "set_script", "delete_script" -> "settings";
            case "commission_build" -> "build/" + buildSite(args);
            case "cancel_build" -> "build/" + args.get("id").getAsString();
            case "create_zone", "create_path", "request_survey" -> intent.path() == null
                    ? intent.action() + "/" + args.get("dimension") + "/" + args.get("from")
                            + "/" + args.get("to") + "/" + args.get("points")
                    : intent.path();
            default -> "action/" + intent.action();
        };
    }

    private static String buildSite(JsonObject args) {
        JsonObject at = args.getAsJsonObject("at");
        if (args.has("points")) {
            return at.get("dimension") + "/" + args.getAsJsonArray("points").asList().stream()
                    .map(point -> ActionTools.pos(point.getAsJsonObject())).toList();
        }
        BlockPos first = ActionTools.pos(at);
        BlockPos last = args.has("to") ? ActionTools.pos(args.getAsJsonObject("to")) : first;
        return at.get("dimension") + "/" + BlockPos.min(first, last) + "/" + BlockPos.max(first, last);
    }

    private static boolean dependsOn(ActionBatch.Intent intent, ToolReply preview, ActionBatch.Intent creator,
            ToolReply createdPreview) {
        boolean createsState = creator.path() != null && creator.before() != null
                && creator.before().isJsonNull() && !creator.after().isJsonNull();
        if (createsState && references(intent.arguments(), creator.path())) {
            return true;
        }
        // Facilities can use the cells of a new zone. Paths can connect to a new zone or station.
        if (creator.action().equals("create_zone")) {
            var area = creator.arguments();
            BlockPos from = ActionTools.pos(area.getAsJsonObject("from"));
            BlockPos to = ActionTools.pos(area.getAsJsonObject("to"));
            var zone = new FacilityExtent(realm(area), BlockPos.min(from, to), BlockPos.max(from, to));
            return switch (intent.action()) {
                case "set_facility" -> FacilityTools.previewExtent(preview).overlaps(zone);
                case "create_path" -> connects(intent.arguments(), zone);
                default -> false;
            };
        }
        return createsState && intent.action().equals("create_path") && creator.action().equals("set_facility")
                && (intent.path() == null || !references(creator.arguments(), intent.path()))
                && connects(intent.arguments(), FacilityTools.previewExtent(createdPreview));
    }

    private static boolean connects(JsonObject path, FacilityExtent extent) {
        var points = path.getAsJsonArray("points").asList();
        Realm realm = realm(path);
        return List.of(points.getFirst(), points.getLast()).stream().anyMatch(point ->
                extent.contains(new WorldPos(realm, ActionTools.pos(point.getAsJsonObject()))));
    }

    private static Realm realm(JsonObject args) {
        return Realm.of(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(args.get("dimension").getAsString())));
    }

    private static boolean references(JsonElement value, String path) {
        if (value.isJsonObject()) {
            return value.getAsJsonObject().asMap().values().stream().anyMatch(child -> references(child, path));
        }
        if (value.isJsonArray()) {
            return value.getAsJsonArray().asList().stream().anyMatch(child -> references(child, path));
        }
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && (value.getAsString().equals(path) || value.getAsString().startsWith(path + "/"));
    }
}
