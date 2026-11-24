package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import io.github.izakyl.folkways.assistant.sandbox.Sandbox;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.StateRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.api.ToolSpec;
import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.facility.api.Rig;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyScopes;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ResourceLocationException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class FacilityTools {

    private static final Gson JSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private static final TypeToken<Map<String, Object>> PLAIN = new TypeToken<>() { };

    private FacilityTools() {
    }

    static void register(StateRegistry states) {
        var operations = states.operations();
        var fields = ActionTools.fields("name:string", "definition:object");
        operations.registerInternal(new ActionRegistry.Spec(ToolRegistry.spec("set_facility", "Write a facility definition. The facility owns every element in extent.elements.",
                fields, "name", "definition"), "settings", false, true), FacilityTools::change);
        operations.registerInternal(new ActionRegistry.Spec(ToolRegistry.spec("delete_facility", "Delete a facility",
                ActionTools.fields("name:string"), "name"), "settings", true), FacilityTools::change);
        var run = ActionTools.fields("facility:string", "routine:string", "args:object");
        run.add("definition", new JsonObject());
        operations.registerInternal(new ActionRegistry.Spec(ToolRegistry.spec("run", "Run a manual facility routine", run,
                "facility", "routine"), "facilities", false), FacilityTools::run);

        operations.registerInternal(new ActionRegistry.Spec(cancelSpec(), "facilities", true), FacilityTools::cancel);
        var parameter = ActionTools.fields("id:string", "key:string");
        parameter.add("value", new JsonObject());
        operations.registerInternal(new ActionRegistry.Spec(ToolRegistry.spec("set_facility_parameter",
                "Set a facility parameter.", parameter, "id", "key", "value"), "settings", false, true),
                FacilityTools::parameter);
        states.registerCreation("facilities/*", keys -> { }, (keys, value) -> {
            JsonObject args = StateTools.object(value);
            return new StateRegistry.Operation(args.has("points") ? "create_path" : "create_zone", args);
        }, (keys, value) -> {
            JsonObject args = new JsonObject();
            args.addProperty("id", keys.getFirst());
            return new StateRegistry.Operation("erase_zone_or_path", args);
        });
        states.register("facilities/*/parameters/*", keys -> { }, (keys, value) -> {
            JsonObject args = new JsonObject();
            args.addProperty("id", keys.getFirst());
            args.addProperty("key", keys.getLast());
            args.add("value", value);
            return new StateRegistry.Operation("set_facility_parameter", args);
        }, null);
        states.register("facilities/*/definition", keys -> { }, (keys, value) -> operation(keys.getFirst(), value),
                (keys, value) -> operation(keys.getFirst(), value), FacilityTools::stage);
        states.registerField("facilities/*/triggers/*/enabled", 3, List.of("triggers"), false, (parent, value) ->
                parent.has("kind") && (parent.get("kind").equals(JSON.toJsonTree("schedule"))
                        || parent.get("kind").equals(JSON.toJsonTree("condition")))
                        && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean());
        states.registerField("facilities/*/triggers/*/retries", 3, List.of("triggers"), false, (parent, value) ->
                parent.has("kind") && boundedInteger(value, 0, Trigger.Retry.MAX_RETRIES));
        states.registerField("facilities/*/triggers/*/backoff", 3, List.of("triggers"), false, (parent, value) ->
                parent.has("kind") && boundedInteger(value, 1, Trigger.Retry.MAX_BACKOFF));
        states.registerField("facilities/*/parameters/*", 3, List.of("parameters"), false, (parent, value) -> true);
        states.registerField("facilities/*/triggers/*", 3, List.of("triggers"), false,
                (parent, value) -> value.isJsonObject());
        states.registerField("facilities/*/routines/*", 3, List.of("routines"), true,
                (parent, value) -> value.isJsonObject());
        states.register("facilities/*/readings", keys -> { }, null, null);
        states.register("facilities/*/recent", keys -> { }, null, null);
        states.registerCreation("facilities/*/runs/*", keys -> { }, (keys, value) -> {
            JsonObject args = StateTools.object(value);
            args.addProperty("facility", keys.getFirst());
            return new StateRegistry.Operation("run", args);
        }, (keys, value) -> {
            JsonObject args = new JsonObject();
            args.addProperty("facility", keys.getFirst());
            args.addProperty("run_id", keys.getLast());
            return new StateRegistry.Operation("cancel", args);
        });
        states.captureWith(FacilityTools::capture);
        states.captureReplies(FacilityTools::captureRecent);
    }

    private static boolean boundedInteger(JsonElement value, int minimum, int maximum) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        double number = value.getAsDouble();
        return number >= minimum && number <= maximum && number == Math.rint(number);
    }

    private static ToolSpec cancelSpec() {
        return ToolRegistry.spec("cancel", "Cancel an active facility script run.",
                ActionTools.fields("facility:string", "run_id:string"), "facility", "run_id");
    }

    private static ActionRegistry.Plan cancel(ToolContext context, JsonObject args) {
        var facilities = service(context);
        String name = definedName(context, args.get("facility").getAsString());
        UUID id = UUID.fromString(args.get("run_id").getAsString());
        if (!facilities.activeScripts(name).containsKey(id)) {
            throw new ActionRegistry.Refused("unknown_run");
        }
        return new ActionRegistry.Plan(Component.translatable("folkways.facility.preview.cancel", name, id.toString()), args,
                id.toString(), () -> CompletableFuture.completedFuture(facilities.cancelScript(name, id)
                    ? new ToolReply(args, List.of()) : ToolReply.error("unknown_run")));
    }

    static ToolSpec controlsSpec() {
        JsonObject fields = ActionTools.fields("structure:string");
        JsonObject area = ActionTools.fields("dimension:string");
        JsonObject corner = ActionTools.position(false);
        corner.getAsJsonObject("properties").add("dimension", area.get("dimension"));
        area.add("from", corner);
        area.add("to", corner.deepCopy());
        var extent = ActionTools.fields("elements:array");
        extent.getAsJsonObject("elements").add("items", ActionTools.fields("element:object").get("element"));
        var extentSchema = ToolRegistry.spec("extent",
            "An elements list of {space, block}, {space, min, max}, or {entity}. Space has dimension or structure.",
            extent, "elements").schema();
        area.add("elements", extent.get("elements"));
        var areaSchema = ToolRegistry.spec("area", "Inclusive box or facility extent", area).schema();
        areaSchema.addProperty("description", "Supply dimension, from and to for a box, or elements for an extent.");
        fields.add("area", areaSchema);
        fields.add("extent", extentSchema);
        return ToolRegistry.spec("controls", "Discover controls and suggest an editable extent. readings lists probes the blocks suggest "
                + "(such as a reading of the block a part points at), each with its position, config and current reading.", fields);
    }

    private static String path(Operating operating) {
        String key = operating.facility() instanceof FacilityDefinition definition ? definition.name()
                : operating.id().toString();
        return "facilities/" + StateRegistry.segment(key);
    }

    private static List<Operating> facilities(ToolContext context) {
        Map<UUID, Operating> result = new LinkedHashMap<>();
        Operations.all(context.colony()).forEach(operating -> result.putIfAbsent(operating.id(), operating));
        // Defined facilities can be hidden from the book list while still reachable directly.
        DefinedFacilities.of(context.colony()).ifPresent(service -> service.facilities().forEach(definition -> {
            if (!result.containsKey(definition.id())) {
                Operations.find(context.colony(), definition.id()).ifPresent(operating ->
                        result.put(operating.id(), operating));
            }
        }));
        return List.copyOf(result.values());
    }

    private static void capture(ToolContext context, Map<String, JsonElement> values, Map<String, String> aliases) {
        var service = DefinedFacilities.of(context.colony());
        var server = context.player().getServer();
        List<Operating> facilities = facilities(context);
        var ambiguous = new java.util.HashSet<String>();
        for (Operating operating : facilities) {
            String path = path(operating);
            String name = operating instanceof ColonyScopes.Scope scope ? scope.scope().toString()
                    : operating.title().getString();
            String alias = "facilities/" + StateRegistry.segment(name);
            String previous = aliases.putIfAbsent(alias, path);
            if (previous != null && !previous.equals(path)) {
                ambiguous.add(alias);
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", operating.id().toString());
            entry.addProperty("kind", operating.kind().toString());
            entry.addProperty("name", name);
            context.colony().claim(operating.id()).ifPresent(holding -> {
                entry.addProperty("kind", holding.owner().toString());
                if (holding.what() instanceof Claimed.Area || holding.what() instanceof Claimed.Line) {
                    JsonArray positions = new JsonArray();
                    holding.what().cells().forEach(at -> positions.add(ToolText.position(at, server)));
                    entry.add(holding.what() instanceof Claimed.Line ? "points" : "cells", positions);
                }
            });
            values.put(path, entry);
            operating.parameters().forEach((key, value) ->
                    values.put(path + "/parameters/" + StateRegistry.segment(key),
                            JSON.toJsonTree(DefinitionCodec.value(value))));
            var readings = operating.loaded(server)
                    .map(level -> Readings.snapshot(level, operating.facility().readings(level))).orElse(Map.of());
            JsonObject read = new JsonObject();
            readings.forEach((key, value) -> read.add(key, JSON.toJsonTree(DefinitionCodec.readout(value))));
            values.put(path + "/readings", read);
            values.put(path + "/recent", JsonNull.INSTANCE);
            if (operating.facility() instanceof FacilityDefinition definition && service.isPresent()) {
                var defined = service.get();
                if (!defined.runtime(definition.name())) {
                    values.put(path + "/definition", JSON.toJsonTree(definition.data()));
                }
                defined.activeRuns(definition.name()).forEach((id, routine) ->
                        values.put(path + "/runs/" + id, JSON.toJsonTree(Map.of("routine", routine))));
                defined.activeScripts(definition.name()).forEach((id, run) ->
                        values.put(path + "/runs/" + id, JSON.toJsonTree(run)));
            }
        }
        ambiguous.forEach(aliases::remove);
        facilities.forEach(operating -> {
            aliases.remove(path(operating));
            String id = "facilities/" + operating.id();
            if (!id.equals(path(operating))) {
                aliases.put(id, path(operating));
            }
        });
    }

    private static void captureRecent(ToolContext context, Map<String, ToolReply> replies) {
        var service = DefinedFacilities.of(context.colony());
        for (Operating operating : facilities(context)) {
            operating.recent().ifPresent(recent -> {
                var text = new ToolText();
                JsonObject data = JSON.toJsonTree(Map.of("routine", recent.routine(), "ending", recent.ending()))
                        .getAsJsonObject();
                if (operating.facility() instanceof FacilityDefinition definition) {
                    service.flatMap(defined -> defined.recent(definition.name()))
                            .ifPresent(ending -> data.addProperty("time", ending.time()));
                }
                recent.reason().ifPresent(reason -> {
                    if (reason.startsWith("folkways.")) {
                        data.add("reason", text.text(Component.translatable(reason)));
                    } else {
                        data.addProperty("reason", reason);
                    }
                });
                replies.put(path(operating) + "/recent", text.reply(data));
            });
        }
    }

    private static ActionRegistry.Plan parameter(ToolContext context, JsonObject args) {
        String id = args.get("id").getAsString();
        String key = args.get("key").getAsString();
        var operating = find(context, id).orElseThrow(() -> new ActionRegistry.Refused("unknown_facility"));
        if (context.colony().claim(operating.id()).isPresent()
                && operating.loaded(context.player().getServer()).isEmpty()) {
            throw new ActionRegistry.Refused("unloaded");
        }
        var before = operating.parameters().get(key);
        var element = operating.card().parameter(key);
        if (before == null && element.isEmpty()) {
            throw new ActionRegistry.Refused("unknown_setting");
        }
        JsonElement input = args.get("value");
        if (element.orElse(null) instanceof Card.Element.Items
                && input.isJsonArray()) {
            input = JSON.toJsonTree(Map.of("items", input));
        }
        Value value = DefinitionCodec.arguments(plain(JSON.toJsonTree(Map.of("value", input)))).get("value");
        if (element.isPresent() && !element.get().accepts(value)) {
            throw new ActionRegistry.Refused("invalid_setting_value");
        }
        Component summary = ActionSummaries.parameter(operating, key, input);
        String fingerprint = operating.id() + "/" + JSON.toJson(before == null ? null : DefinitionCodec.value(before));
        return new ActionRegistry.Plan(summary, args, fingerprint, () -> {
            requireValid(operating.setParameter(key, value));
            return CompletableFuture.completedFuture(new ToolReply(args, List.of()));
        });
    }

    static String definedName(ToolContext context, String key) {
        return find(context, key).map(Operating::facility).filter(FacilityDefinition.class::isInstance)
                .map(FacilityDefinition.class::cast).map(FacilityDefinition::name).orElse(key);
    }

    static Optional<Operating> find(ToolContext context, String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException ignored) {
            uuid = null;
        }
        if (uuid != null) {
            var found = Operations.find(context.colony(), uuid);
            if (found.isPresent()) {
                return found;
            }
        }
        return facilities(context).stream().filter(operating ->
                operating.facility() instanceof FacilityDefinition definition && definition.name().equals(id))
                .findFirst();
    }

    private static JsonElement stage(JsonElement before, JsonElement after) {
        JsonObject result = after.getAsJsonObject().deepCopy();
        for (String field : List.of("parameters", "triggers", "routines")) {
            if (!result.has(field)) {
                result.add(field, new JsonObject());
            }
        }
        return result;
    }

    private static StateRegistry.Operation operation(String name, JsonElement value) {
        JsonObject args = new JsonObject();
        args.addProperty("name", name);
        if (value.isJsonNull()) {
            return new StateRegistry.Operation("delete_facility", args);
        }
        args.add("definition", value);
        return new StateRegistry.Operation("set_facility", args);
    }

    private static ActionRegistry.Plan change(ToolContext context, JsonObject args) {
        DefinedFacilities facilities = service(context);
        String name = args.get("name").getAsString();
        if (facilities.runtime(name) || find(context, name).filter(operating ->
                !(operating.facility() instanceof FacilityDefinition)).isPresent()) {
            throw new ActionRegistry.Refused("state_read_only");
        }
        var preview = facilities.preview(name, Optional.ofNullable(args.get("definition")).map(FacilityTools::plain));
        requireValid(preview.problems());
        var covered = sources(preview.covered(), context.player());
        var restored = sources(preview.restored(), context.player());
        JsonObject details = JSON.toJsonTree(Map.of("facility", name, "change",
                preview.change().name().toLowerCase(Locale.ROOT), "covered", covered, "restored", restored,
                "before_projections", projections(preview.before()), "after_projections", projections(preview.after()),
                "used_stores", preview.usedStores().stream().map(WorldPos::toString).toList(),
                "before_protection", preview.before().map(data -> data.get("extent")).orElse(Map.of()),
                "after_protection", preview.after().map(data -> data.get("extent")).orElse(Map.of()))).getAsJsonObject();
        var summary = preview.describe().copy();
        int beforeGates = projections(preview.before()).size();
        int afterGates = projections(preview.after()).size();
        if (beforeGates != 0 || afterGates != 0) {
            summary.append("\n").append(Component.translatable(
                "folkways.facility.preview.projections", beforeGates, afterGates));
        }
        JsonObject change = new JsonObject();
        change.add("details", details);
        return new ActionRegistry.Plan(summary, change, JSON.toJsonTree(preview.before().orElse(Map.of())).toString(),
                () -> {
                    requireValid(facilities.apply(preview));
                    return CompletableFuture.completedFuture(new ToolReply(details, List.of()));
                });
    }

    private static List<?> projections(Optional<Map<String, Object>> definition) {
        return (List<?>) definition.map(data -> data.get("projections")).orElse(List.of());
    }

    private static ActionRegistry.Plan run(ToolContext context, JsonObject args) {
        var facilities = service(context);
        String name = definedName(context, args.get("facility").getAsString());
        String routine = args.get("routine").getAsString();
        JsonElement definition = args.get("definition");
        Map<String, Value> arguments = args.has("args") ? DefinitionCodec.arguments(plain(args.get("args"))) : Map.of();
        if (facilities.runtime(name)) {
            requireValid(facilities.previewRun(name, routine, arguments));
            JsonObject result = JSON.toJsonTree(Map.of("facility", name, "routine", routine)).getAsJsonObject();
            return new ActionRegistry.Plan(Component.translatable("folkways.facility.preview.run", name, routine),
                result, "{}", () -> {
                    requireValid(facilities.previewRun(name, routine, arguments));
                    requireValid(facilities.run(name, routine, arguments));
                    return CompletableFuture.completedFuture(new ToolReply(result, List.of()));
                });
        }
        if (definition.isJsonNull()) {
            requireValid(List.of(new DefinedFacilities.Problem("name", "missing", name)));
        }
        var data = plain(definition);
        requireValid(facilities.previewRun(routine, arguments, data));
        // Canonicalize optional defaults, just as DefinedFacilities.preview does for a staged replacement.
        var expected = FacilityDefinition.fromData(UUID.randomUUID(), data).data();
        JsonObject result = JSON.toJsonTree(Map.of("facility", name, "routine", routine)).getAsJsonObject();
        return new ActionRegistry.Plan(Component.translatable("folkways.facility.preview.run", name, routine),
                result, JSON.toJsonTree(expected).toString(), () -> {
                    if (!facilities.read(name).map(DefinedFacilities.View::data).equals(Optional.of(expected))) {
                        return CompletableFuture.completedFuture(ToolReply.error("stale"));
                    }
                    requireValid(facilities.run(name, routine, arguments));
                    return CompletableFuture.completedFuture(new ToolReply(result, List.of()));
                });
    }

    static FacilityExtent previewExtent(ToolReply preview) {
        return DefinitionCodec.extent(plain(preview.data().getAsJsonObject("details").get("after_protection")));
    }

    /** The protected extent a staged definition replaces; empty for a new facility. */
    static FacilityExtent previousExtent(ToolReply preview) {
        Map<String, Object> before = plain(preview.data().getAsJsonObject("details").get("before_protection"));
        return before.isEmpty() ? FacilityExtent.EMPTY : DefinitionCodec.extent(before);
    }

    static void checkOverlaps(List<ActionBatch.Intent> intents, List<ToolReply> previews) {
        Map<String, FacilityExtent> staged = new LinkedHashMap<>();
        for (int index = 0; index < intents.size(); index++) {
            if (!intents.get(index).action().equals("set_facility")) {
                continue;
            }
            var details = previews.get(index).data().getAsJsonObject("details");
            String name = details.get("facility").getAsString();
            FacilityExtent extent = previewExtent(previews.get(index));
            for (var previous : staged.entrySet()) {
                if (extent.overlaps(previous.getValue())) {
                    requireValid(List.of(new DefinedFacilities.Problem(name + ".extent", "overlap", previous.getKey())));
                }
            }
            staged.put(name, extent);
        }
    }

    private static void requireValid(List<DefinedFacilities.Problem> problems) {
        if (!problems.isEmpty()) {
            var summary = Component.empty();
            for (var problem : problems) {
                if (!summary.getSiblings().isEmpty()) {
                    summary.append("\n");
                }
                summary.append(problem.describe());
            }
            throw new ActionRegistry.Refused("invalid_facility", problems.getFirst().reason(), summary);
        }
    }

    private static Map<String, Object> plain(JsonElement value) {
        return JSON.fromJson(value, PLAIN);
    }

    private static DefinedFacilities service(ToolContext context) {
        return DefinedFacilities.of(context.colony()).orElseThrow(() -> new ActionRegistry.Refused("facilities_unavailable"));
    }

    static ToolReply controls(ServerPlayer player, JsonObject args) {
        try {
            var colony = CurrentColony.of(player).orElseThrow(() -> new ActionRegistry.Refused("not_member"));
            var context = new ToolContext(player, colony);
            var facilities = service(context);
            ReadScope scope = WorldReads.permitted(player) ? null : new ReadScope(context);
            if (java.util.stream.Stream.of("area", "structure", "extent").filter(args::has).count() != 1) {
                throw new ActionRegistry.Refused("invalid_arguments");
            }
            ServerLevel level = player.serverLevel();
            FacilityExtent extent;
            if (args.has("structure")) {
                UUID structure = UUID.fromString(args.get("structure").getAsString());
                extent = FacilityExtent.structure(level, structure)
                        .orElseThrow(() -> new ActionRegistry.Refused("unloaded"));
            } else if (args.has("extent") || args.getAsJsonObject("area").has("elements")) {
                extent = DefinitionCodec.extent(plain(args.has("extent") ? args.get("extent") : args.get("area")));
                level = extent.firstBlock().flatMap(at -> at.level(player.getServer())).orElse(level);
            } else {
                var area = args.getAsJsonObject("area");
                if (!area.has("dimension") || !area.has("from") || !area.has("to")) {
                    throw new ActionRegistry.Refused("invalid_arguments");
                }
                var dimension = ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(area.get("dimension").getAsString()));
                level = player.getServer().getLevel(dimension);
                if (level == null) {
                    throw new ActionRegistry.Refused("unloaded");
                }
                var from = ActionTools.pos(area.getAsJsonObject("from"));
                var to = ActionTools.pos(area.getAsJsonObject("to"));
                extent = new FacilityExtent(Realm.of(level), BlockPos.min(from, to), BlockPos.max(from, to));
            }
            if (Sandbox.is(level)) {
                throw new ActionRegistry.Refused("unloaded");
            }
            if (scope != null && !inScope(level, scope, extent)) {
                throw new ActionRegistry.Refused("outside_survey");
            }
            ServerLevel source = level;
            var found = facilities.discover(level, extent, at -> scope == null || inScope(source, scope, at));
            var prefabs = found.prefabs().stream()
                    .filter(prefab -> scope == null || inScope(source, scope, prefab.anchor())).toList();
            JsonObject result = JSON.toJsonTree(Map.of("extent", DefinitionCodec.extent(found.extent()),
                    "controls", found.controls().stream().map(candidate -> candidate(candidate, player)).toList(),
                    "prefabs", sources(prefabs, player),
                    "prefab_conflicts", prefabConflicts(level, prefabs),
                    "readings", hints(level, found.extent(), player, scope),
                    "overlaps", found.overlaps().stream()
                            .filter(overlap -> scope == null || inScope(source, scope, overlap.extent())).map(overlap -> Map.of("colony", overlap.colony().toString(),
                            "name", overlap.name(), "extent", DefinitionCodec.extent(overlap.extent()))).toList(),
                    "colony_id", colony.id().toString(), "capture_tick", level.getGameTime())).getAsJsonObject();
            result.add("parts", Guide.parts(Guide.rows(result.getAsJsonArray("controls")), player.clientInformation().language()));
            return new ToolReply(result, List.of());
        } catch (IllegalArgumentException | ResourceLocationException invalid) {
            return ActionRegistry.error(invalid);
        }
    }

    static boolean inScope(ServerLevel level, ReadScope scope, FacilityExtent extent) {
        return DefinedFacilities.checkExtentBounds(level, extent).isEmpty()
                && extent.cells().stream().allMatch(at -> inScope(level, scope, at))
                && extent.elements().stream().allMatch(element -> !(element instanceof FacilityExtent.Entity entity)
                        || Optional.ofNullable(level.getEntity(entity.id()))
                                .map(WorldSpaces::at).filter(scope::contains).isPresent());
    }

    private static boolean inScope(ServerLevel level, ReadScope scope, WorldPos at) {
        return WorldSpaces.storage(level, at).filter(pos -> scope.contains(level, pos)).isPresent();
    }

    // Readings the blocks in the extent suggest, such as a reading of the block a part points at.
    private static List<?> hints(ServerLevel level, FacilityExtent extent, ServerPlayer player, ReadScope scope) {
        List<Object> found = new java.util.ArrayList<>();
        for (var cell : extent.cells()) {
            for (var hint : Readings.hints(level, cell)) {
                if (scope != null && !inScope(level, scope, hint.at())) {
                    continue;
                }
                JsonObject entry = ToolText.position(hint.at(), player.getServer());
                entry.addProperty("probe", hint.probe().toString());
                if (!hint.config().isEmpty()) {
                    JsonObject config = new JsonObject();
                    hint.config().forEach((key, value) -> config.add(key, JSON.toJsonTree(DefinitionCodec.value(value))));
                    entry.add("config", config);
                }
                Readings.of(hint.probe(), hint.at(), hint.config()).flatMap(reading -> reading.read(level))
                        .ifPresent(value -> entry.add("reading", JSON.toJsonTree(DefinitionCodec.value(value))));
                entry.addProperty("about", hint.about());
                found.add(entry);
            }
        }
        return found;
    }

    private static List<?> sources(List<Facilities.Source> sources, ServerPlayer player) {
        return sources.stream().map(source -> Map.of("id", source.id().toString(),
                "anchor", ToolText.position(source.anchor(), player.getServer()))).toList();
    }

    private static List<Map<String, String>> prefabConflicts(ServerLevel level, List<Facilities.Source> prefabs) {
        return prefabs.stream().filter(source -> source.id() instanceof UUID).flatMap(source -> {
            Facilities.ownerOf(level, source.anchor());
            return Facilities.ownershipConflict(level, (UUID) source.id()).stream().map(winner ->
                Map.of("facility", source.id().toString(), "owner", winner.toString()));
        }).toList();
    }

    private static JsonObject candidate(DefinedFacilities.Candidate candidate, ServerPlayer player) {
        var at = candidate.position();
        JsonObject result = ToolText.position(at.at(), player.getServer());
        result.addProperty("part", at.part());
        candidate.entity().ifPresent(entity -> result.addProperty("entity", entity.toString()));
        switch (candidate.kind()) {
            case Rig.Signalled signal -> {
                result.addProperty("kind", "signal");
                result.add("type", JSON.toJsonTree(signalType(signal.type())));
            }
            case Rig.Sensed sensor -> {
                result.addProperty("kind", "reading");
                result.addProperty("maximum", sensor.maximum());
            }
            case Rig.Ported port -> {
                result.addProperty("kind", "port");
                result.addProperty("resource", port.resource().id().toString());
            }
        }
        candidate.reading().ifPresent(value -> result.add("reading", JSON.toJsonTree(DefinitionCodec.readout(value))));
        return result;
    }

    private static Map<String, ?> signalType(SignalType type) {
        return switch (type) {
            case SignalType.Real real -> Map.of("kind", "real", "min", real.min(), "max", real.max(),
                    "step", real.step());
            case SignalType.Vector vector -> Map.of("kind", "vector",
                    "axes", vector.axes().stream().map(FacilityTools::signalType).toList());
            case SignalType.Bool ignored -> Map.of("kind", "bool");
            case SignalType.Pulse ignored -> Map.of("kind", "pulse");
            case SignalType.Data ignored -> Map.of("kind", "data");
        };
    }
}
