package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;
import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.colony.internal.PointSites;
import io.github.izakyl.folkways.core.front.api.read.Railways;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.create.rail.domain.RailContent;
import io.github.izakyl.folkways.vanilla.create.rail.domain.RailPresence;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.createmod.catnip.data.Couple;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = AssistantMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class RailReadsGameTests {

    private static final ResourceLocation OWNER = ResourceLocation.parse("folkways_vanilla:rail_read_test");

    static {
        PointSites.register(OWNER, (level, pos) -> true);
    }

    private RailReadsGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if (ModList.get().isLoaded("create")) {
            event.register(RailReadsGameTests.class);
        }
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "rail_reads", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void placedRailsExposeStationsEndsAndAConnectableDraft(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.line(fixture.base, 6);
        fixture.line(fixture.base.offset(0, 0, 24), 6);
        helper.runAfterDelay(10, () -> {
            var level = helper.getLevel();
            var manager = Create.RAILWAYS.sided(level);
            TrackGraph local = fixture.graph(fixture.base);
            TrackGraph remote = fixture.graph(fixture.base.offset(0, 0, 24));
            GlobalStation near = fixture.station(local, fixture.base.offset(2, 0, 2), "Local");
            GlobalStation far = fixture.station(remote, fixture.base.offset(2, 0, 26), "Remote A");
            GlobalStation hidden = fixture.station(remote, fixture.base.offset(3, 0, 26), "Other");
            Train train = new Train(UUID.randomUUID(), fixture.player.getUUID(), remote,
                    List.of(), List.of(), false, 0);
            DestinationInstruction destination = new DestinationInstruction();
            destination.getData().putString("Text", "Remote*");
            Schedule schedule = new Schedule();
            schedule.entries.add(new ScheduleEntry(destination, List.of()));
            train.runtime.schedule = schedule;
            var presence = fixture.colony.service(RailContent.ID, RailPresence.class).orElseThrow();
            presence.take(train.id);
            manager.trains.put(train.id, train);
            JsonObject[] found = {null};
            try {
                fixture.warm();
                var palette = new DimensionPalette();
                var graphBefore = local.write(level.registryAccess(), palette).copy();
                var scheduleBefore = train.runtime.write(level.registryAccess()).copy();
                Purity.assertPure(helper, "rail provider and script reads", () -> {
                    var source = Railways.source(Railways.RAIL).orElseThrow();
                    helper.assertTrue(source.stations(level.getServer(), fixture.colony).size() >= 3,
                            "provider reads graph edge points");
                    ToolReply stations = fixture.read("rail_stations", new JsonObject());
                    ok(helper, stations);
                    var rows = stations.data().getAsJsonArray("stations");
                    helper.assertTrue(hasStation(rows, near.id), "authorized station is visible");
                    helper.assertTrue(hasStation(rows, far.id), "claimed train serves remote wildcard station");
                    helper.assertFalse(hasStation(rows, hidden.id), "other remote station stays private");
                    var farRow = rows.asList().stream().map(value -> value.getAsJsonObject())
                            .filter(row -> row.get("id").getAsString().equals(far.id.toString()))
                            .findFirst().orElseThrow();
                    helper.assertTrue(farRow.get("served_by_colony_train").getAsBoolean(), "served flag");
                    helper.assertTrue(farRow.getAsJsonObject("at").get("z").getAsInt()
                            == far.getBlockEntityPos().getZ(), "station block position, not track position");
                    var tracks = fixture.tracks(fixture.base.offset(-8, -2, -8), fixture.base.offset(8, 2, 32));
                    helper.assertTrue(tracks.getAsJsonArray("nodes").size() >= 2, "only local graph nodes");
                    helper.assertFalse(tracks.getAsJsonArray("edges").isEmpty(), "local edges");
                    for (String field : List.of("nodes", "edges", "ends")) {
                        helper.assertTrue(tracks.getAsJsonArray(field).asList().stream().allMatch(row ->
                                row.getAsJsonObject().get("graph").getAsString().equals(local.id.toString())),
                                "remote served station does not authorize " + field);
                    }
                    helper.assertTrue(tracks.getAsJsonArray("ends").size() == 2, "two degree-one ends");
                    for (var value : tracks.getAsJsonArray("ends")) {
                        var end = value.getAsJsonObject();
                        Vec3 direction = vector(end.getAsJsonObject("direction"));
                        helper.assertTrue(Math.abs(direction.length() - 1) < 1e-6, "outward unit vector");
                        BlockPos lastRail = direction.x > 0 ? fixture.base.east(5) : fixture.base;
                        BlockPos expected = (direction.x > 0 ? lastRail.east() : lastRail.west()).below();
                        helper.assertTrue(vector(end.getAsJsonObject("path_point"))
                                .equals(Vec3.atLowerCornerOf(expected)),
                                "path point is the ground cell adjacent to " + lastRail);
                        if (direction.x > 0) {
                            found[0] = end;
                        }
                    }
                });
                helper.assertTrue(graphBefore.equals(local.write(level.registryAccess(), palette)),
                        "reads preserve Create graph data");
                helper.assertTrue(scheduleBefore.equals(train.runtime.write(level.registryAccess())),
                        "reads preserve train schedule");
                BlockPos tipCell = BlockPos.containing(vector(found[0].getAsJsonObject("at")));
                var tipOnly = fixture.tracks(tipCell, tipCell);
                helper.assertTrue(tipOnly.getAsJsonArray("ends").size() == 1, "the queried end is visible");
                helper.assertTrue(tipOnly.getAsJsonArray("ends").get(0).getAsJsonObject()
                        .get("path_point").isJsonNull(), "suggestions outside the query are omitted");
                JsonObject page = new JsonObject();
                page.addProperty("limit", 1);
                List<String> ids = new ArrayList<>();
                for (int index = 0; index < 8; index++) {
                    var data = fixture.read("rail_stations", page).data();
                    data.getAsJsonArray("stations").forEach(row -> ids.add(row.getAsJsonObject()
                            .get("id").getAsString()));
                    if (data.get("next_cursor").isJsonNull()) {
                        break;
                    }
                    page.add("cursor", data.get("next_cursor"));
                }
                helper.assertTrue(ids.contains(near.id.toString()) && ids.contains(far.id.toString())
                        && ids.size() == ids.stream().distinct().count(), "pages have no repeated station");
                JsonObject limit = fixture.box(fixture.base.offset(-8, -2, -8), fixture.base.offset(8, 2, 8));
                limit.addProperty("limit", 1);
                helper.assertTrue(fixture.read("rail_tracks", limit).data().get("limited").getAsBoolean(),
                        "node limit is explicit");
            } catch (RuntimeException | AssertionError failure) {
                fixture.close();
                throw failure;
            } finally {
                manager.trains.remove(train.id);
                presence.release(train.id);
                local.removePoint(EdgePointType.STATION, near.id);
                remote.removePoint(EdgePointType.STATION, far.id);
                remote.removePoint(EdgePointType.STATION, hidden.id);
            }
            JsonObject tip = found[0];
            helper.assertTrue(tip != null, "east-facing open end");
            BlockPos point = BlockPos.containing(vector(tip.getAsJsonObject("path_point")));
            level.getChunk(point.east(24));
            String start = xyz(point).toString();
            String finish = xyz(point.east(24)).toString();
            var check = new ScriptHost().run(fixture.player,
                    "result = draft(\"folkways_vanilla:railway\", \"" + level.dimension().location()
                            + "\", points=[" + start + ", " + finish + "])", new JsonObject()).thenAccept(reply -> {
                                ok(helper, reply);
                                var result = reply.data().getAsJsonObject("result");
                                helper.assertTrue(result.get("ok").getAsBoolean(),
                                        "rail_tracks path point previews an extension: " + reply.localized());
                                var anchors = result.getAsJsonArray("anchors");
                                helper.assertTrue(vector(anchors.get(0).getAsJsonObject())
                                        .equals(Vec3.atLowerCornerOf(point)), "draft returns the ground anchor");
                                var connections = result.getAsJsonObject("rail_connections");
                                helper.assertTrue(connections.get("start").getAsBoolean()
                                        && !connections.get("end").getAsBoolean()
                                        && connections.get("connected").getAsInt() == 1, "only the near end connects");
                            });
            AsyncWait.succeedWhen(helper, check, () -> { }, fixture::close);
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "rail_clipping")
    public static void geometryIsClippedAcrossScopeGapsAndNeverLoadsChunks(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            var level = helper.getLevel();
            fixture.colony.hold(OWNER, new Claimed.Point(WorldPos.of(level, fixture.base.east(32))))
                    .orElseThrow();
            TrackGraph graph = new TrackGraph();
            TrackNode a = node(level, fixture.base.offset(-20, 0, 4), graph);
            TrackNode b = node(level, fixture.base.offset(64, 0, 4), graph);
            connect(graph, a, b, null);
            var manager = Create.RAILWAYS.sided(level);
            manager.trackNetworks.put(graph.id, graph);
            try {
                level.getChunk(fixture.base.east(32));
                fixture.warm();
                var palette = new DimensionPalette();
                var before = graph.write(level.registryAccess(), palette).copy();
                Purity.assertPure(helper, "stations on a cold graph", () ->
                        ok(helper, fixture.read("rail_stations", new JsonObject())));
                helper.assertTrue(before.equals(graph.write(level.registryAccess(), palette)),
                        "station reads never insert an empty station map");
                Purity.assertPure(helper, "clipped geometry", () -> {
                    var data = fixture.tracks(fixture.base.offset(-24, -1, 0), fixture.base.offset(70, 1, 8));
                    var edges = data.getAsJsonArray("edges");
                    helper.assertTrue(edges.size() == 2, "one edge split at the authorization gap: " + data);
                    helper.assertTrue(data.getAsJsonArray("nodes").isEmpty(), "no external endpoint coordinates");
                    helper.assertTrue(data.getAsJsonArray("ends").isEmpty(), "cuts never become open ends");
                    for (var value : edges) {
                        var row = value.getAsJsonObject();
                        double x = row.getAsJsonObject("from").get("x").getAsDouble() - fixture.base.getX();
                        double to = row.getAsJsonObject("to").get("x").getAsDouble() - fixture.base.getX();
                        helper.assertTrue(x >= -8 && to < 9 || x >= 24 && to < 41, "fragment stays authorized");
                        helper.assertTrue(row.get("length").getAsDouble() <= 17, "hidden length excluded");
                    }
                    var narrow = fixture.tracks(fixture.base.offset(-2, -1, 0), fixture.base.offset(2, 1, 8));
                    helper.assertTrue(narrow.getAsJsonArray("edges").get(0).getAsJsonObject()
                            .get("length").getAsDouble() <= 5, "query bounds also clip edges");
                    var outside = fixture.tracks(fixture.base.offset(12, -1, 0), fixture.base.offset(18, 1, 8));
                    helper.assertTrue(outside.getAsJsonArray("edges").isEmpty(), "no authorization by connectivity");
                });
                BlockPos unloaded = fixture.base.east(5000);
                fixture.colony.hold(OWNER, new Claimed.Line(List.of(WorldPos.of(level, unloaded),
                        WorldPos.of(level, unloaded.east(5))))).orElseThrow();
                TrackGraph distant = new TrackGraph();
                connect(distant, node(level, unloaded, distant), node(level, unloaded.east(5), distant), null);
                manager.trackNetworks.put(distant.id, distant);
                try {
                    helper.assertTrue(level.getChunkSource().getChunkNow(unloaded.getX() >> 4,
                            unloaded.getZ() >> 4) == null, "fixture chunk is unloaded");
                    Purity.assertPure(helper, "unloaded railway", () -> {
                        var data = fixture.tracks(unloaded.offset(-1, -1, -1), unloaded.offset(6, 1, 1));
                        for (String field : List.of("nodes", "edges", "ends")) {
                            helper.assertTrue(data.getAsJsonArray(field).isEmpty(), "unloaded " + field);
                        }
                    });
                } finally {
                    manager.trackNetworks.remove(distant.id);
                }
                Charters.keep(fixture.colony, Charter.founded("Revoked", UUID.randomUUID()));
                helper.assertTrue(fixture.read("rail_stations", new JsonObject()).data().get("error")
                        .getAsString().equals("not_member"), "station reads recheck membership");
                helper.assertTrue(fixture.read("rail_tracks", fixture.box(fixture.base, fixture.base))
                        .data().get("error").getAsString().equals("not_member"), "geometry rechecks membership");
            } finally {
                manager.trackNetworks.remove(graph.id);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "rail_curves")
    public static void curvesSampleEveryTwoBlocksAndRespectTheSampleCap(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            var level = helper.getLevel();
            BlockPos to = fixture.base.offset(160, 0, 160);
            fixture.colony.hold(OWNER, new Claimed.Line(List.of(WorldPos.of(level, fixture.base),
                    WorldPos.of(level, to)))).orElseThrow();
            TrackGraph graph = new TrackGraph();
            Vec3 from = Vec3.atBottomCenterOf(fixture.base);
            Vec3 end = Vec3.atBottomCenterOf(to);
            Vec3 diagonal = end.subtract(from).normalize();
            BezierConnection curve = new BezierConnection(Couple.create(fixture.base, to), Couple.create(from, end),
                    Couple.create(diagonal, diagonal.scale(-1)), Couple.create(new Vec3(0, 1, 0), new Vec3(0, 1, 0)),
                    true, false, TrackMaterial.ANDESITE);
            connect(graph, node(level, fixture.base, graph), node(level, to, graph), curve);
            var manager = Create.RAILWAYS.sided(level);
            manager.trackNetworks.put(graph.id, graph);
            try {
                for (int step = 0; step <= 160; step++) {
                    level.getChunk(fixture.base.offset(step, 0, step));
                }
                fixture.warm();
                Purity.assertPure(helper, "curve samples", () -> {
                    var data = fixture.tracks(fixture.base.offset(-2, -2, -2), to.offset(2, 2, 2));
                    var edges = data.getAsJsonArray("edges");
                    int count = edges.asList().stream().mapToInt(value -> value.getAsJsonObject()
                            .getAsJsonArray("samples").size()).sum();
                    helper.assertTrue(count == 64 && data.get("limited").getAsBoolean(),
                            "64 samples per original edge across fragments: " + count);
                    for (var value : edges) {
                        var edge = value.getAsJsonObject();
                        helper.assertTrue(edge.get("curve").getAsBoolean(), "curve flag");
                        var samples = edge.getAsJsonArray("samples");
                        for (int index = 1; index < samples.size(); index++) {
                            double distance = vector(samples.get(index).getAsJsonObject())
                                    .distanceTo(vector(samples.get(index - 1).getAsJsonObject()));
                            helper.assertTrue(Math.abs(distance - 2) < 0.001, "two block sampling interval");
                        }
                    }
                });
            } finally {
                manager.trackNetworks.remove(graph.id);
            }
        }
        helper.succeed();
    }

    private static TrackNode node(ServerLevel level, BlockPos at, TrackGraph graph) {
        TrackNode node = new TrackNode(new TrackNodeLocation(Vec3.atBottomCenterOf(at)).in(level),
                TrackGraph.nextNodeId(), new Vec3(0, 1, 0));
        graph.addNode(node);
        return node;
    }

    private static void connect(TrackGraph graph, TrackNode a, TrackNode b, BezierConnection curve) {
        graph.putConnection(a, b, new TrackEdge(a, b, curve, TrackMaterial.ANDESITE));
        graph.putConnection(b, a, new TrackEdge(b, a, curve == null ? null : curve.secondary(),
                TrackMaterial.ANDESITE));
    }

    private static boolean hasStation(JsonArray stations, UUID id) {
        return stations.asList().stream().anyMatch(row -> row.getAsJsonObject().get("id").getAsString()
                .equals(id.toString()));
    }

    private static Vec3 vector(JsonObject at) {
        return new Vec3(at.get("x").getAsDouble(), at.get("y").getAsDouble(), at.get("z").getAsDouble());
    }

    private static JsonObject xyz(BlockPos pos) {
        JsonObject row = new JsonObject();
        row.addProperty("x", pos.getX());
        row.addProperty("y", pos.getY());
        row.addProperty("z", pos.getZ());
        return row;
    }

    private static void ok(GameTestHelper helper, ToolReply reply) {
        helper.assertFalse(reply.data().has("error"), reply.data().toString());
    }

    private static final class Fixture implements AutoCloseable {

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final Colony colony;
        private final BlockPos base;
        private final List<BlockPos> placed = new ArrayList<>();

        private Fixture(GameTestHelper helper) {
            this.helper = helper;
            var level = helper.getLevel();
            base = helper.absolutePos(new BlockPos(1, 30, 1));
            player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "rail-read"),
                    ClientInformation.createDefault());
            colony = Charters.found(player, "Rail reads");
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            level.getChunk(base);
            colony.hold(OWNER, new Claimed.Point(WorldPos.of(level, base))).orElseThrow();
        }

        private void warm() {
            for (var holding : colony.claims()) {
                for (var cell : holding.what().cells()) {
                    helper.getLevel().getChunkSource().getChunkNow(cell.cell().getX() >> 4, cell.cell().getZ() >> 4);
                }
            }
        }

        private void line(BlockPos start, int length) {
            try {
                var track = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),
                        "create:track[shape=xo]", false).blockState();
                for (int index = 0; index < length; index++) {
                    BlockPos at = start.east(index);
                    helper.getLevel().setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
                    helper.getLevel().setBlock(at, track, 3);
                    placed.add(at);
                    placed.add(at.below());
                }
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        private TrackGraph graph(BlockPos at) {
            return Create.RAILWAYS.sided(helper.getLevel()).trackNetworks.values().stream().filter(graph ->
                    graph.getNodes().stream().anyMatch(node -> node.getDimension().equals(helper.getLevel().dimension())
                            && node.getLocation().distanceTo(Vec3.atBottomCenterOf(at)) < 2)).findFirst().orElseThrow();
        }

        private GlobalStation station(TrackGraph graph, BlockPos at, String name) {
            var level = helper.getLevel();
            level.setBlock(at, BuiltInRegistries.BLOCK.get(
                    ResourceLocation.parse("create:track_station")).defaultBlockState(), 3);
            placed.add(at);
            TrackNode node = graph.getNodes().stream().map(graph::locateNode)
                    .filter(candidate -> !graph.getConnectionsFrom(candidate).isEmpty()).findFirst().orElseThrow();
            TrackNode neighbor = graph.getConnectionsFrom(node).keySet().iterator().next();
            GlobalStation station = EdgePointType.STATION.create();
            station.setId(UUID.randomUUID());
            station.name = name;
            station.blockEntityDimension = level.dimension();
            station.blockEntityPos = at;
            station.setLocation(Couple.create(node.getLocation(), neighbor.getLocation()), 1);
            graph.addPoint(EdgePointType.STATION, station);
            return station;
        }

        private JsonObject box(BlockPos from, BlockPos to) {
            JsonObject args = new JsonObject();
            JsonObject start = xyz(from);
            JsonObject end = xyz(to);
            start.addProperty("dimension", helper.getLevel().dimension().location().toString());
            end.addProperty("dimension", helper.getLevel().dimension().location().toString());
            args.add("at", start);
            args.add("to", end);
            return args;
        }

        private JsonObject tracks(BlockPos from, BlockPos to) {
            ToolReply reply = read("rail_tracks", box(from, to));
            ok(helper, reply);
            return reply.data();
        }

        private ToolReply read(String name, JsonObject args) {
            return ToolRegistry.READS.execute(player, name, args);
        }

        @Override
        public void close() {
            colony.raze();
            for (BlockPos at : placed) {
                helper.getLevel().setBlock(at, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }
}
