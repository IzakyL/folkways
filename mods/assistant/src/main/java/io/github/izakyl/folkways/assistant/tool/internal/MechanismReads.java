package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import io.github.izakyl.folkways.assistant.sandbox.Probe;
import io.github.izakyl.folkways.assistant.sandbox.Sandbox;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.api.ToolSpec;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan;
import io.github.izakyl.folkways.core.facility.api.mechanism.Mechanisms;
import io.github.izakyl.folkways.core.script.api.ColonyScripts;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.ResourceLocationException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Two reads for calibrating a facility over a built mechanism: {@code mechanism} lays out how it is put together, and
 * {@code probe} works one control on a copy in the assistant's sandbox and says what moved, moving structures such as
 * a ship or a walker included. Neither touches the world.
 */
final class MechanismReads {

    static final int MAX_BYTES = 24 * 1024;
    private static final Gson JSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private static final TypeToken<Map<String, Object>> PLAIN = new TypeToken<>() { };

    private MechanismReads() {
    }

    static void register(ToolRegistry.DirectReads registry) {
        registry.register(graphSpec(), (player, args) -> CompletableFuture.completedFuture(graph(player, args)));
        registry.register(probeSpec(), MechanismReads::probe);
    }

    private static JsonObject scope() {
        JsonObject fields = ActionTools.fields("structure:string");
        JsonObject area = ActionTools.fields("dimension:string");
        area.add("from", ActionTools.position(false));
        area.add("to", ActionTools.position(false));
        fields.add("area", ToolRegistry.spec("area", "Inclusive box", area, "dimension", "from", "to").schema());
        fields.add("at", ReadTools.positionSchema());
        return fields;
    }

    static ToolSpec graphSpec() {
        return ToolRegistry.spec("mechanism", "Read how a built mechanism works, as a graph. Give one of area (an "
                + "inclusive box, at most 64 per side, with the moving structures standing in it), at (a seed block: "
                + "the mechanical blocks joined to it, plus one block around) or structure (a moving structure's "
                + "UUID, in its own coordinates). nodes are parts {id, kind, roles, at, state, controls?}: sources, "
                + "controls, transmissions, actuators, moving structures. edges {from, to, link, ...}: drives (ratio "
                + "= speed at to per speed at from; plain shafts and gears are folded into via), powers (redstone "
                + "reach), controls, moves. paths {from, to, factor, rpm_per_forward_turn?, through}: how a source "
                + "or crank's rotation reaches each actuator. parts summarizes the control IDs shown in the nodes. Static: it says how parts connect, not which way an "
                + "actuator moves; use probe for that. At most 128 nodes; trimmed with truncated=true to 24 KiB.",
                scope());
    }

    static ToolSpec probeSpec() {
        JsonObject fields = scope();
        ActionTools.fields("routine:string", "facility:string", "definition:object", "args:object", "keep:array")
            .entrySet().forEach(field -> fields.add(field.getKey(), field.getValue()));
        JsonObject control = new JsonObject();
        control.addProperty("type", "object");
        fields.add("control", control);
        fields.add("value", new JsonObject());
        JsonObject redstone = ActionTools.fields("x:integer", "y:integer", "z:integer", "on:boolean");
        fields.add("redstone", ToolRegistry.spec("redstone", "Redstone input", redstone, "x", "y", "z", "on")
                .schema());
        JsonObject ticks = new JsonObject();
        ticks.addProperty("type", "integer");
        fields.add("ticks", ticks);
        fields.add("release_tick", ticks.deepCopy());
        return ToolRegistry.spec("probe", "Trial a mechanism input or a whole facility routine and report what changed. "
                + "For a routine that breaks, places or moves blocks, first call probe(routine=<routine>, "
                + "definition=<definition> or facility=<id>, args=<arguments>). Only run it in the world after "
                + "routine.ending is done and no cells changed outside what the player allowed. "
                + "Debug it with trials. Routine trials take ticks (default 600, at most 2400) and report routine "
                + "{ending, reason?, steps, actions, collected?} and outside {count, cells} if a target box "
                + "or keep=[x0,y0,z0,x1,y1,z1] is given. For one input, use area, at or structure as for "
                + "mechanism. That trial takes ticks (default 40, at most 100; "
                + "20 ticks a second) without changing the world. Input: control (a dict with x, y, z and part, "
                + "such as a controls() or mechanism() entry; structure for a moving structure's local cell) "
                + "with value written as a "
                + "facility routine would (a Create crank's turn takes whole uses, + forward, - backward; a lever "
                + "True/False; omit value for a pulse), or redstone {x, y, z, on} to put a redstone block at an "
                + "empty cell. Moving structures, with the structures attached to them, are tested whole with the "
                + "ground up to 16 blocks around them, and move, walk and turn as in the world. Returns input "
                + "{result, done_tick}, changes [{id, before, after, peak_speed}] (a moving structure's position), "
                + "entities [{id, moved [dx,dy,dz], changed}], structures [{id, moved [dx,dy,dz], turned "
                + "{yaw, pitch, roll} in degrees (yaw + clockwise seen from above), at, ended_tick?}], blocks "
                + "{added, removed, altered}, suppressed (projectiles and drops never spawn), notes, summary. One "
                + "probe at a time on the server; a trial includes at most 16384 blocks and 32 structures. Weapons never "
                + "fire a projectile during a trial. Set release_tick (less than ticks) to return the channel to "
                + "its prior value and measure signed velocity, start and stop latency, overshoot and dead zones "
                + "for each actuator. Repeat at each candidate level, including hub terminal levels 0–15.", fields);
    }

    private static ReadScope scope(ServerPlayer player) {
        var colony = CurrentColony.of(player).orElseThrow(() -> new IllegalArgumentException("not_member"));
        return WorldReads.permitted(player) ? null : new ReadScope(new ToolContext(player, colony));
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw new IllegalArgumentException("outside_survey");
        }
    }

    private static void checkScan(ReadScope scope, MechanismScan scan) {
        if (scope == null) {
            return;
        }
        require(scan.cells().stream().allMatch(cell -> scope.contains(scan.level(), cell.storage())));
        require(scan.entities().stream()
                .allMatch(entity -> scope.contains(WorldSpaces.at(entity))));
        for (var frame : scan.frames()) {
            var extent = FacilityExtent.structure(scan.level(), frame.id()).orElseThrow();
            require(extent.cells().stream().allMatch(scope::contains));
        }
    }

    static ToolReply graph(ServerPlayer player, JsonObject args) {
        try {
            ReadScope scope = scope(player);
            MechanismScan scan = scan(player, args, scope);
            checkScan(scope, scan);
            MechanismGraph graph = Mechanisms.graph(scan);
            CalibrationTrace.safely(() -> CalibrationTrace.mechanism(player, scan, graph));
            return reply(player, render(graph, scan));
        } catch (IllegalArgumentException | ResourceLocationException invalid) {
            return error(invalid);
        }
    }

    static CompletableFuture<ToolReply> probe(ServerPlayer player, JsonObject args) {
        ReadScope scope;
        Probe.Request request;
        try {
            scope = scope(player);
            if (args.has("routine")) {
                request = routine(player, args, scope);
            } else {
                if (Stream.of("facility", "definition", "args", "keep").anyMatch(args::has)) {
                    throw new IllegalArgumentException("invalid_arguments: routine is required");
                }
                MechanismScan scan = scan(player, args, scope);
                request = new Probe.Request(scan, input(scan, args), args.has("ticks")
                        ? ActionTools.integer(args.get("ticks")) : Probe.DEFAULT_TICKS,
                    args.has("release_tick") ? ActionTools.integer(args.get("release_tick")) : 0);
            }
            checkScan(scope, request.scan());
            if (scope != null) {
                WorldPos at = switch (request.input()) {
                    case Probe.Input.Write write -> write.at();
                    case Probe.Input.Redstone redstone -> redstone.at();
                    case Probe.Input.Routine routine -> routine.facility().anchor();
                };
                var level = request.scan().level();
                require(WorldSpaces.storage(level, at).filter(pos -> scope.contains(level, pos)).isPresent());
            }
        } catch (IllegalArgumentException | ResourceLocationException | IllegalStateException invalid) {
            return CompletableFuture.completedFuture(error(invalid));
        }
        long started = player.serverLevel().getGameTime();
        UUID trace = UUID.randomUUID();
        Probe.Request tried = request;
        CalibrationTrace.safely(() -> CalibrationTrace.probeStarted(player, trace, tried));
        return Sandbox.probe(player, request, pos -> scope == null || scope.contains(tried.scan().level(), pos))
                .thenApply(data -> {
                    CalibrationTrace.safely(() -> CalibrationTrace.probed(player, trace, tried, data));
                    data.addProperty("capture_tick", started);
                    return reply(player, data);
                });
    }

    private static Probe.Request routine(ServerPlayer player, JsonObject query, ReadScope scope) {
        if (query.has("facility") == query.has("definition") || Stream.of("area", "at", "structure", "control",
                "value", "redstone", "release_tick").anyMatch(query::has)) {
            throw new IllegalArgumentException("invalid_arguments: a routine needs either facility or definition");
        }
        var colony = CurrentColony.of(player).orElseThrow();
        FacilityDefinition facility;
        if (query.has("definition")) {
            var data = object(query, "definition");
            facility = FacilityDefinition.fromData(UUID.randomUUID(), data);
            checkFacility(player, scope, facility);
            var service = DefinedFacilities.of(colony)
                .orElseThrow(() -> new IllegalArgumentException("facilities_unavailable"));
            var preview = service.preview(facility.name(), Optional.of(data));
            if (!preview.problems().isEmpty()) {
                var problem = preview.problems().getFirst();
                throw new IllegalArgumentException("invalid_facility: " + problem);
            }
        } else {
            facility = FacilityTools.find(new ToolContext(player, colony), query.get("facility").getAsString())
                .map(operating -> operating.facility()).filter(FacilityDefinition.class::isInstance)
                .map(FacilityDefinition.class::cast).orElseThrow(() -> new IllegalArgumentException("unknown_facility"));
            checkFacility(player, scope, facility);
        }
        var args = query.has("args") ? DefinitionCodec.arguments(object(query, "args")) : Map.<String, Value>of();
        var keep = query.has("keep") ? Optional.of(Probe.Input.Routine.box(value(query.get("keep")))
            .orElseThrow(() -> new IllegalArgumentException("invalid_arguments: keep must be a six-integer box")))
            : Optional.<net.minecraft.world.level.levelgen.structure.BoundingBox>empty();
        var input = new Probe.Input.Routine(facility, query.get("routine").getAsString(), args,
            ColonyScripts.snapshot(colony), keep);
        int ticks = query.has("ticks") ? ActionTools.integer(query.get("ticks")) : Probe.DEFAULT_ROUTINE_TICKS;
        return Probe.Request.routine(player.getServer(), input, ticks);
    }

    private static void checkFacility(ServerPlayer player, ReadScope scope, FacilityDefinition facility) {
        if (scope != null) {
            var level = facility.anchor().level(player.getServer())
                    .filter(found -> !Sandbox.is(found)).orElseThrow(() -> new IllegalArgumentException("unloaded"));
            require(scope.contains(facility.anchor()) && FacilityTools.inScope(level, scope, facility.extent()));
        }
    }

    private static Map<String, Object> object(JsonObject args, String field) {
        if (!args.get(field).isJsonObject()) {
            throw new IllegalArgumentException("invalid_arguments: " + field + " must be a dict");
        }
        return JSON.fromJson(args.get(field), PLAIN.getType());
    }

    private static ToolReply error(RuntimeException invalid) {
        String message = invalid.getMessage() == null ? "invalid_arguments" : invalid.getMessage();
        return message.matches("[a-z_]+(: .*)?") ? withMessage(message) : ToolReply.error("invalid_arguments");
    }

    private static ToolReply withMessage(String message) {
        int colon = message.indexOf(": ");
        ToolReply reply = ToolReply.error(colon < 0 ? message : message.substring(0, colon));
        if (colon >= 0) {
            reply.data().addProperty("message", message.substring(colon + 2));
            return new ToolReply(reply.data(), List.of());
        }
        return reply;
    }

    private static MechanismScan scan(ServerPlayer player, JsonObject args, ReadScope scope) {
        long given = Stream.of("area", "at", "structure").filter(args::has).count();
        if (given != 1) {
            throw new IllegalArgumentException("invalid_arguments: give exactly one of area, at or structure");
        }
        if (args.has("structure")) {
            UUID structure = UUID.fromString(args.get("structure").getAsString());
            for (ServerLevel level : player.getServer().getAllLevels()) {
                if (!Sandbox.is(level) && WorldSpaces.frame(level, structure).isPresent()) {
                    if (scope != null) {
                        require(FacilityExtent.structure(level, structure)
                                .filter(extent -> FacilityTools.inScope(level, scope, extent)).isPresent());
                    }
                    return MechanismScan.structure(level, structure);
                }
            }
            throw new IllegalArgumentException("unloaded");
        }
        if (args.has("at")) {
            JsonObject at = args.getAsJsonObject("at");
            ServerLevel level = level(player, at.get("dimension").getAsString());
            BlockPos seed = ActionTools.pos(at);
            require(scope == null || scope.contains(level, seed));
            return MechanismScan.seed(level, seed);
        }
        JsonObject area = args.getAsJsonObject("area");
        ServerLevel level = level(player, area.get("dimension").getAsString());
        BlockPos from = ActionTools.pos(area.getAsJsonObject("from"));
        BlockPos to = ActionTools.pos(area.getAsJsonObject("to"));
        if (scope != null) {
            require(scope.contains(level, from) && scope.contains(level, to));
        }
        return MechanismScan.box(level, from, to);
    }

    private static ServerLevel level(ServerPlayer player, String dimension) {
        ServerLevel level = player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(dimension)));
        if (level == null || Sandbox.is(level)) {
            throw new IllegalArgumentException("unloaded");
        }
        return level;
    }

    private static Probe.Input input(MechanismScan scan, JsonObject args) {
        if (args.has("control") == args.has("redstone")) {
            throw new IllegalArgumentException("invalid_arguments: give exactly one of control or redstone");
        }
        if (args.has("redstone")) {
            JsonObject redstone = args.getAsJsonObject("redstone");
            return new Probe.Input.Redstone(new WorldPos(Realm.of(scan.level()), ActionTools.pos(redstone)),
                    redstone.get("on").getAsBoolean());
        }
        JsonObject control = args.getAsJsonObject("control");
        if (!control.has("part") || !control.get("part").isJsonPrimitive()) {
            throw new IllegalArgumentException("invalid_arguments: control needs a part");
        }
        BlockPos cell = ActionTools.pos(control);
        Realm realm = control.has("structure")
                ? new Realm.Frame(UUID.fromString(control.get("structure").getAsString()))
                : scan.realm() instanceof Realm.Frame frame ? frame : Realm.of(scan.level());
        Optional<Value> value = args.has("value") && !args.get("value").isJsonNull()
                ? Optional.of(value(args.get("value"))) : Optional.empty();
        return new Probe.Input.Write(new WorldPos(realm, cell), control.get("part").getAsString(), value);
    }

    private static Value value(JsonElement value) {
        JsonObject wrapped = new JsonObject();
        wrapped.add("value", value);
        Map<String, Object> plain = JSON.fromJson(wrapped, PLAIN.getType());
        return DefinitionCodec.arguments(plain).get("value");
    }

    /** The graph as the tool prints it: positions as [x, y, z], moving-structure parts with their structure. */
    private static JsonObject render(MechanismGraph graph, MechanismScan scan) {
        JsonObject data = new JsonObject();
        JsonObject space = new JsonObject();
        switch (scan.realm()) {
            case Realm.Dimension dimension -> space.addProperty("dimension", dimension.id().location().toString());
            case Realm.Frame frame -> space.addProperty("structure", frame.structure().toString());
        }
        data.add("space", space);
        var box = scan.box();
        data.add("bounds", JSON.toJsonTree(Map.of("from", List.of(box.minX(), box.minY(), box.minZ()),
                "to", List.of(box.maxX(), box.maxY(), box.maxZ()))));
        JsonArray nodes = new JsonArray();
        for (MechanismGraph.Node node : graph.nodes()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", node.id());
            row.addProperty("kind", node.kind());
            row.add("roles", JSON.toJsonTree(node.roles().stream().sorted()
                    .map(role -> role.name().toLowerCase(Locale.ROOT)).toList()));
            row.addProperty("block", node.block().toString());
            BlockPos cell = node.at().cell();
            row.add("at", JSON.toJsonTree(List.of(cell.getX(), cell.getY(), cell.getZ())));
            if (node.at().realm() instanceof Realm.Frame(UUID structure) && !(scan.realm() instanceof Realm.Frame)) {
                row.addProperty("structure", structure.toString());
            }
            node.entity().ifPresent(id -> row.addProperty("entity", id.toString()));
            node.state().forEach((key, value) -> row.add(key, JSON.toJsonTree(value)));
            nodes.add(row);
        }
        data.add("nodes", nodes);
        JsonArray edges = new JsonArray();
        for (MechanismGraph.Edge edge : graph.edges()) {
            JsonObject row = new JsonObject();
            row.addProperty("from", edge.from());
            row.addProperty("to", edge.to());
            row.addProperty("link", edge.link().name().toLowerCase(Locale.ROOT));
            edge.details().forEach((key, value) -> row.add(key, JSON.toJsonTree(value)));
            edges.add(row);
        }
        data.add("edges", edges);
        JsonArray paths = new JsonArray();
        for (MechanismGraph.Path path : graph.paths()) {
            JsonObject row = new JsonObject();
            row.addProperty("from", path.from());
            row.addProperty("to", path.to());
            row.addProperty("factor", path.factor());
            row.addProperty("hops", path.hops());
            if (!path.through().isEmpty()) {
                row.add("through", JSON.toJsonTree(path.through()));
            }
            path.details().forEach((key, value) -> row.add(key, JSON.toJsonTree(value)));
            paths.add(row);
        }
        data.add("paths", paths);
        data.add("unsupported", JSON.toJsonTree(graph.unsupported()));
        data.addProperty("truncated", graph.truncated());
        return data;
    }

    // Keeps the reply within its budget by dropping the last nodes, edges and paths first.
    private static ToolReply reply(ServerPlayer player, JsonObject data) {
        while (true) {
            if (data.has("nodes")) {
                var controls = Guide.rows(data.getAsJsonArray("nodes")).filter(node -> node.has("controls"))
                        .flatMap(node -> Guide.rows(node.getAsJsonArray("controls")));
                data.add("parts", Guide.parts(controls, player.clientInformation().language()));
            }
            if (data.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) {
                break;
            }
            boolean cut = false;
            for (String list : List.of("paths", "edges", "nodes", "changes", "entities", "structures")) {
                if (data.has(list) && data.get(list).isJsonArray() && !data.getAsJsonArray(list).isEmpty()) {
                    JsonArray array = data.getAsJsonArray(list);
                    array.remove(array.size() - 1);
                    cut = true;
                    break;
                }
            }
            data.addProperty("truncated", true);
            if (!cut) {
                return ToolReply.error("result_size_cap");
            }
        }
        return new ToolReply(data, List.of()).capped(player.registryAccess());
    }
}
