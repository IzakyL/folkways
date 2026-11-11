package io.github.izakyl.folkways.core.travel.internal.graph;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.travel.api.Fare;
import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.travel.api.Passage;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Node;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WaypointDensityGameTests {

    private static final ResourceLocation KIND = id("density_test_resident");
    private static final ResourceLocation SERVICE = id("density_test_passage");
    private static final Sort SORT = new Sort(id("walking"), new Bulk(1, 2));

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(WaypointDensityGameTests.class);
    }

    @SubscribeEvent
    public static void declare(Declaring event) {
        event.passage(new Passage() {
            public ResourceLocation id() {
                return SERVICE;
            }

            public List<Hop> hopsIn(Colony colony) {
                return List.of();
            }

            public Conveyance aboard(Hop hop) {
                throw new UnsupportedOperationException("graph-only test passage");
            }
        });
        Participations.register(KIND, Stake.passage(SERVICE), Participation.ALWAYS);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void overlappingCoverageJoinsExistingPointsWithoutJunctions(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        int a = net.pin(cell(0, 1, 0), 0);
        int b = net.pin(cell(12, 1, 0), 0);
        long shared = cell(6, 1, 0);
        net.remember(shared, a, 0);
        Waypoints.Net before = net.freeze(0);
        for (int now = 1; now <= 10; now++) {
            net.remember(shared, b, now);
            net.remember(net.cellOf(a), b, now);
        }
        helper.assertTrue(net.size() == 2 && net.at(shared) < 0 && net.reaching(shared) == a,
            "attached cells and existing waypoints never promote an overlap to a junction");
        helper.assertTrue(net.links() == 2 && net.joins(a, b, 10) && net.joins(b, a, 10)
            && !net.apart(a, b), "the holders join in both directions, including their pieces");
        helper.assertTrue(!before.joined(a, b) && before.reaching(shared) == a,
            "joining leaves the published snapshot intact");
        net.walked(net.cellOf(a), net.cellOf(b), 200, 11);
        int learned = net.linksFrom(a).getFirst().ticks;
        net.wrongEdge(net.cellOf(a), net.cellOf(b), 12);
        net.remember(shared, b, 13);
        helper.assertTrue(net.linksFrom(a).getFirst().ticks == learned && !net.joins(a, b, 13),
            "repeated coverage neither overwrites learned costs nor revives a suspended edge");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void overlappingCoverageUsesJunctionWhenDirectLinkIsUnsuitable(GameTestHelper helper) {
        long shared = cell(6, 1, 0);
        Waypoints levels = new Waypoints();
        int low = levels.pin(cell(0, 0, 0), 0);
        int high = levels.pin(cell(12, 2, 0), 0);
        levels.remember(shared, low, 0);
        levels.remember(shared, high, 1);
        int junction = levels.at(shared);
        helper.assertTrue(levels.size() == 3 && levels.links() == 4 && junction >= 0
            && levels.joins(low, high, 1) && levels.joins(high, low, 1) && !levels.apart(low, high),
            "overlap at an intermediate height joins holders two levels apart through a junction");
        helper.assertTrue(levels.linksFrom(low).getFirst().to == junction
            && levels.linksFrom(high).getFirst().to == junction,
            "the slope uses both one-level legs rather than a direct two-level link");

        Waypoints closed = new Waypoints();
        int west = closed.pin(cell(0, 1, 0), 0);
        int east = closed.pin(cell(20, 1, 0), 0);
        long around = cell(10, 1, 10);
        closed.remember(around, west, 0);
        closed.closeOff(new BoundingBox(8, 0, -2, 12, 4, 2));
        closed.remember(around, east, 1);
        int detour = closed.at(around);
        helper.assertTrue(closed.size() == 3 && closed.links() == 4 && detour >= 0
            && closed.joins(west, east, 1) && closed.joins(east, west, 1)
            && closed.linksFrom(west).getFirst().to == detour
            && closed.linksFrom(east).getFirst().to == detour,
            "clear legs to an overlap join around closed ground through a junction");
        closed.remember(cell(10, 1, 0), east, 1);
        helper.assertTrue(closed.reaching(cell(10, 1, 0)) < 0, "closed ground cannot gain coverage");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void rememberingAttachedCellKeepsNetUnchanged(GameTestHelper helper) throws Exception {
        Waypoints net = new Waypoints();
        int holder = net.pin(cell(0, 1, 0), 0);
        long attached = cell(6, 1, 0);
        net.remember(attached, holder, 0);
        Waypoints.Net before = net.freeze(0);
        for (int now = 1; now <= 10; now++) {
            net.remember(attached, holder, now);
            helper.assertTrue(!net.changed(now) && !(boolean) field(net, "attachedMoved"),
                "remembering the same holder changes neither the net nor its frozen attachments");
        }
        helper.assertTrue(net.size() == 1 && net.links() == 0 && net.reaching(attached) == holder
            && before.reaching(attached) == holder, "coverage remains attached to its original holder");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void rambleReusesCoveredCellsAndStillLaysNewGround(GameTestHelper helper) throws Exception {
        Waypoints net = new Waypoints();
        int a = net.pin(cell(0, 1, 0), 0);
        int b = net.pin(cell(26, 1, 0), 0);
        for (int x = 1; x <= 25; x++) {
            net.remember(cell(x, 1, 0), x < 13 ? a : b, 0);
        }
        List<Node> covered = trail(1, 26);
        Ramble ramble = new Ramble(helper.getLevel(), Realm.of(helper.getLevel()), net, null, cell(1, 1, 0));
        Method harvest = Ramble.class.getDeclaredMethod("harvest", Reading.class, List.class, long.class);
        harvest.setAccessible(true);
        for (long now = 1; now <= 3; now++) {
            harvest.invoke(ramble, reading(covered), covered, now);
        }
        helper.assertTrue(net.size() == 2 && net.at(cell(1, 1, 0)) < 0
            && net.reaching(cell(1, 1, 0)) == a && net.reaching(cell(13, 1, 0)) == b,
            "even a covered sweep root and long covered trails reuse their original holders");
        helper.assertTrue(net.joins(a, b, 3) && net.joins(b, a, 3) && !net.apart(a, b),
            "meeting coverage from a loose piece still joins it");
        List<Node> extended = trail(1, 50);
        harvest.invoke(ramble, reading(extended), extended, 4L);
        helper.assertTrue(net.size() > 2 && net.reaching(cell(50, 1, 0)) >= 0
            && net.joins(a, net.reaching(cell(50, 1, 0)), 4), "uncovered ground still gains a connected route");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    @SuppressWarnings("unchecked")
    public static void platformPinsClustersAndKeepsCrossingHubsRoutable(GameTestHelper helper) throws Exception {
        Realm ground = Realm.of(helper.getLevel());
        Realm aboard = new Realm.Frame(UUID.randomUUID());
        Stances.Cells boarding = footings(ground);
        Stances.Cells landing = footings(aboard);
        Method hubOf = Roaming.class.getDeclaredMethod("hubOf", Stances.Cells.class);
        hubOf.setAccessible(true);
        WorldPos on = ((Optional<WorldPos>) hubOf.invoke(null, boarding)).orElseThrow();
        WorldPos off = ((Optional<WorldPos>) hubOf.invoke(null, landing)).orElseThrow();
        helper.assertTrue(on.cell().getX() == 6 && off.cell().getX() == 6,
            "the actual hub differs from the default least-cell representative");
        Roaming roaming = new Roaming(helper.getLevel());
        Waypoints lower = new Waypoints();
        Waypoints upper = new Waypoints();
        Method platform = Roaming.class.getDeclaredMethod("platform", Waypoints.class, Tract.class,
            Stances.Cells.class, WorldPos.class, long.class);
        platform.setAccessible(true);
        platform.invoke(roaming, lower, new Tract(SORT, ground), boarding, on, 0L);
        platform.invoke(roaming, upper, new Tract(SORT, aboard), landing, off, 0L);
        for (Waypoints net : List.of(lower, upper)) {
            helper.assertTrue(net.size() == 2 && ((BitSet) field(net, "pinned")).cardinality() == 2,
                "each of the two disconnected clusters pins just one representative");
            helper.assertTrue(net.at(cell(6, 1, 0)) >= 0 && net.at(cell(0, 1, 0)) < 0
                && net.reaching(cell(0, 1, 0)) == net.at(cell(6, 1, 0))
                && net.reaching(cell(21, 1, 0)) == net.at(cell(20, 1, 0)),
                "the hub represents its cluster and other footings keep exact coverage beyond snap range");
        }
        Set<?> fresh = (Set<?>) field(roaming, "platforms");
        helper.assertTrue(fresh.size() == 2, "fresh platforms retain their initial sweep");
        fresh.clear();
        lower.freeze(0);
        upper.freeze(0);
        platform.invoke(roaming, lower, new Tract(SORT, ground), boarding, on, 1L);
        platform.invoke(roaming, upper, new Tract(SORT, aboard), landing, off, 1L);
        helper.assertTrue(fresh.isEmpty() && lower.size() == 2 && upper.size() == 2,
            "known platforms neither queue another fresh sweep nor grow points");
        helper.assertTrue(!lower.changed(1) && !upper.changed(1)
            && !(boolean) field(lower, "attachedMoved") && !(boolean) field(upper, "attachedMoved"),
            "re-pinning the same platforms changes neither net nor its frozen attachments");
        Hop hop = new Hop(SERVICE, boarding, landing, Fare.flat(20));
        WayGraph ways = new WayGraph(Map.of(KIND, SORT), Map.of(new Tract(SORT, ground), lower.freeze(1),
            new Tract(SORT, aboard), upper.freeze(1)), List.of(new Crossing(on, off, hop, SERVICE)),
            Map.of(), new Asked(), new Trodden(), List.of(), 1);
        Resident who = new Resident(UUID.randomUUID(), KIND);
        for (int x : List.of(0, 6, 21)) {
            WorldPos from = new WorldPos(ground, new BlockPos(x, 1, 0));
            WorldPos to = new WorldPos(aboard, new BlockPos(x, 1, 0));
            helper.assertTrue(ways.journey(who, from, Set.of(to)) instanceof Faring.Yes
                && ways.reckon(who, from, Set.of(to)) instanceof Faring.Yes,
                "Web resolves hub and attached crossing endpoints for journey and planning at x=" + x);
        }
        helper.succeed();
    }

    private static Stances.Cells footings(Realm realm) {
        Set<WorldPos> cells = new LinkedHashSet<>();
        for (int x : List.of(0, 1, 2, 3, 4, 5, 6, 20, 21)) {
            cells.add(new WorldPos(realm, new BlockPos(x, 1, 0)));
        }
        return new Stances.Cells(cells);
    }

    private static List<Node> trail(int from, int to) {
        List<Node> nodes = new ArrayList<>();
        Node previous = null;
        for (int x = from; x <= to; x++) {
            Node node = new Node(x, 1, 0);
            node.cameFrom = previous;
            nodes.add(node);
            previous = node;
        }
        return nodes;
    }

    private static Reading reading(List<Node> nodes) {
        return new Reading(new Ground.Open(),
            new Sweep.Swept(nodes, nodes, true, false, null, nodes.size()));
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static long cell(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, path);
    }
}
