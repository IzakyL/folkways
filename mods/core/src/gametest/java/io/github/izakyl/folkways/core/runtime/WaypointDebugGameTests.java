package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.engine.net.DebugWaypointsPacket;
import io.github.izakyl.folkways.core.front.engine.net.RequestDebugWaypointsPacket;
import io.github.izakyl.folkways.core.resident.api.Locomotions;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.graph.Roamer;
import io.github.izakyl.folkways.core.travel.internal.graph.Roaming;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraph;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.Edge;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.EdgeKind;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.Point;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.PointKind;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.Snapshot;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.walk.Walking;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WaypointDebugGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(WaypointDebugGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void publishedFloorHasTypedBoundedReadOnlyWaypoints(GameTestHelper helper)
            throws ReflectiveOperationException {
        // Span more than the waypoint spacing along the template's long axis to witness links, not just attachments.
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 26; z++) {
                helper.setBlock(x, 1, z, Blocks.STONE);
            }
        }
        Mob body = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        var level = helper.getLevel();
        var kind = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "resident");
        var roamers = List.of(new Roamer(kind, Walking.INSTANCE, body));
        var resident = new Resident(body.getUUID(), kind);
        WorldPos from = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 2, 1)));
        WorldPos to = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 2, 25)));
        Roaming roaming = new Roaming(level);
        Faring reached = roaming.answered(ways -> ways.journey(resident, from, Set.of(to)),
            roamers, List.of(), level.getGameTime());
        helper.assertTrue(reached instanceof Faring.Yes, "the walkable floor builds a usable graph");
        WayGraph graph = (WayGraph) roaming.ways();
        Vec3 centre = Vec3.atBottomCenterOf(from.cell());
        Snapshot snapshot = WayGraphDebug.read(level, graph, centre);
        helper.assertTrue(snapshot.points().size() >= 2 && !snapshot.edges().isEmpty(),
            "published ways contain points and links");
        helper.assertTrue(snapshot.points().stream().anyMatch(point -> point.kind() == PointKind.FOOT)
                && snapshot.points().stream().anyMatch(point -> point.kind() == PointKind.WAYPOINT),
            "resident feet are distinguished from ordinary waypoints");
        helper.assertTrue(snapshot.edges().stream().allMatch(edge -> edge.kind() == EdgeKind.NORMAL
                && edge.sort().equals(Locomotions.WALKING)
                && edge.from() < snapshot.points().size() && edge.to() < snapshot.points().size()),
            "newly witnessed paths are normal walking edges with included endpoints");
        helper.assertTrue(snapshot.points().stream().allMatch(point ->
                Vec3.atBottomCenterOf(point.pos()).distanceToSqr(centre) <= 48 * 48),
            "all points lie within the requested world radius");
        Snapshot near = WayGraphDebug.read(level, graph, centre, 1, 2000, 4000);
        helper.assertTrue(near.points().size() < snapshot.points().size() && near.edges().isEmpty()
                && !near.truncated(), "radius filtering omits distant points and their links without truncation");
        Snapshot capped = WayGraphDebug.read(level, graph, centre, 48, 1, 4000);
        helper.assertTrue(capped.points().size() == 1 && capped.edges().isEmpty() && capped.truncated(),
            "the point cap marks truncation and never leaves dangling links");
        Snapshot edgeCapped = WayGraphDebug.read(level, graph, centre, 48, 2000, 0);
        helper.assertTrue(edgeCapped.points().equals(snapshot.points()) && edgeCapped.edges().isEmpty()
                && edgeCapped.truncated(), "the edge cap marks truncation independently of the point cap");
        Snapshot distant = WayGraphDebug.read(level, graph, centre.add(100, 0, 0));
        helper.assertTrue(distant.points().isEmpty() && distant.edges().isEmpty() && !distant.truncated(),
            "a distant observer receives no graph");
        helper.assertTrue(ColonyWays.published(UUID.randomUUID(), level).isEmpty(),
            "looking for an absent colony graph does not create one");
        helper.assertTrue(WayGraphDebug.read(level, graph, centre).equals(snapshot) && roaming.ways() == graph,
            "diagnostic reads neither change nor rebuild published ways");
        Edge edge = snapshot.edges().getFirst();
        WorldPos leaving = WorldPos.of(level, snapshot.points().get(edge.from()).pos());
        WorldPos arriving = WorldPos.of(level, snapshot.points().get(edge.to()).pos());
        roaming.wrongEdge(leaving, arriving);
        // Freeze the feedback without a new search repairing the edge before it can be inspected.
        publish(roaming, graph, roamers, level.getGameTime());
        Snapshot failed = WayGraphDebug.read(level, (WayGraph) roaming.ways(), centre);
        helper.assertTrue(failed.edges().stream().anyMatch(link -> link.kind() == EdgeKind.STRUCK_PROBATION),
            "failed links survive the routing freeze as struck probation diagnostics");
        helper.assertTrue(WayGraphDebug.read(level, graph, centre).equals(snapshot),
            "new feedback leaves the previous published diagnostics immutable");
        roaming.walkedEdge(leaving, arriving, 10);
        publish(roaming, graph, roamers, level.getGameTime());
        Snapshot probation = WayGraphDebug.read(level, (WayGraph) roaming.ways(), centre);
        helper.assertTrue(probation.edges().stream().anyMatch(link -> link.kind() == EdgeKind.PROBATION),
            "a successful walk clears strikes while retaining the existing retry deadline");
        roundTrip(helper, failed);
        roundTrip(helper, probation);
        roundTrip(helper, snapshot);
        roundTrip(helper, capped);
        roundTrip(helper, Snapshot.EMPTY);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void packetPreservesEveryKindAndRejectsDanglingEdges(GameTestHelper helper) {
        var points = new ArrayList<Point>();
        for (PointKind kind : PointKind.values()) {
            points.add(new Point(helper.absolutePos(new BlockPos(kind.ordinal(), 2, 1)), kind));
        }
        var edges = new ArrayList<Edge>();
        for (EdgeKind kind : EdgeKind.values()) {
            edges.add(new Edge(0, 1, kind, kind == EdgeKind.CROSSING
                ? ResourceLocation.fromNamespaceAndPath("folkways_core", "test_passage") : Locomotions.WALKING));
        }
        roundTrip(helper, new Snapshot(points, edges, true));
        boolean refused = false;
        try {
            new DebugWaypointsPacket(UUID.randomUUID(), helper.getLevel().dimension(),
                new Snapshot(points, List.of(new Edge(0, points.size(), EdgeKind.NORMAL, Locomotions.WALKING)), false));
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        helper.assertTrue(refused, "a packet cannot reference an endpoint outside its point array");
        var request = new RequestDebugWaypointsPacket(UUID.randomUUID());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            RequestDebugWaypointsPacket.STREAM_CODEC.encode(buffer, request);
            helper.assertTrue(request.equals(RequestDebugWaypointsPacket.STREAM_CODEC.decode(buffer))
                    && !buffer.isReadable(), "the request preserves colony identity without trailing bytes");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    private static void publish(Roaming roaming, WayGraph graph, List<Roamer> roamers, long now)
            throws ReflectiveOperationException {
        var sorts = WayGraph.class.getDeclaredField("sorts");
        sorts.setAccessible(true);
        var publish = Roaming.class.getDeclaredMethod("publish", Map.class, List.class, List.class,
            long.class, boolean.class);
        publish.setAccessible(true);
        publish.invoke(roaming, sorts.get(graph), roamers, List.of(), now, true);
    }

    private static void roundTrip(GameTestHelper helper, Snapshot snapshot) {
        var packet = new DebugWaypointsPacket(UUID.randomUUID(), helper.getLevel().dimension(), snapshot);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            DebugWaypointsPacket.STREAM_CODEC.encode(buffer, packet);
            helper.assertTrue(packet.equals(DebugWaypointsPacket.STREAM_CODEC.decode(buffer)) && !buffer.isReadable(),
                "the packet preserves context, positions, types, directed endpoints, sorts and truncation");
        } finally {
            buffer.release();
        }
    }
}
