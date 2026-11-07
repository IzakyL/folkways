package io.github.izakyl.folkways.core.travel.internal.graph;

import io.github.izakyl.folkways.FolkwaysMod;
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
import io.github.izakyl.folkways.core.travel.internal.Journey;
import io.github.izakyl.folkways.core.travel.internal.Leg;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.Closures;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ClosureGameTests {

    private static final ResourceLocation KIND = ResourceLocation.fromNamespaceAndPath("folkways", "resident");

    private static final Sort SORT = new Sort(ResourceLocation.fromNamespaceAndPath("folkways", "walking"),
        new Bulk(1, 2));

    private static final ResourceLocation SERVICE = ResourceLocation.fromNamespaceAndPath("folkways", "test_closure_passage");

    @SubscribeEvent
    public static void declare(Declaring event) {
        event.passage(new Passage() {
            public ResourceLocation id() { return SERVICE; }
            public List<Hop> hopsIn(Colony colony) { return List.of(); }
            public Conveyance aboard(Hop hop) { throw new UnsupportedOperationException("graph-only test passage"); }
        });
        Participations.register(KIND, Stake.passage(SERVICE), Participation.ALWAYS);
    }

    // The closed ground: x 8 to 12 across the line the ways run along.
    private static final BoundingBox BOX = new BoundingBox(8, 0, -2, 12, 4, 2);

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ClosureGameTests.class);
    }

    @GameTest(template = "empty")
    public static void closingTakesUpTheWaysInAndAcrossIt(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        int west = net.pin(cell(0, 0), 0);
        int inside = net.pin(cell(10, 0), 0);
        int east = net.pin(cell(20, 0), 0);
        int north = net.pin(cell(0, 10), 0);
        both(net, west, inside);
        both(net, inside, east);
        both(net, west, east);
        both(net, west, north);
        net.closeOff(BOX);
        net.freeze(1);
        helper.assertTrue(net.at(cell(10, 0)) < 0, "the waypoint in the closed ground is gone");
        helper.assertTrue(!net.joins(west, east, 1), "no way runs through the closed ground");
        helper.assertTrue(net.joins(west, north, 1), "ways clear of it stay");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void noWayIsStraightenedAcrossClosedGround(GameTestHelper helper) {
        helper.assertValueEqual(bend(false).straighten(1), 1, "open, a bend round the ground is straightened");
        Waypoints closed = bend(true);
        helper.assertValueEqual(closed.straighten(1), 0, "closed, the bend stays");
        closed.reopen(BOX);
        helper.assertValueEqual(closed.straighten(2), 1, "opened again, it is straightened");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void closedWorkIsReachedNativelyWithoutPublicWaypoints(GameTestHelper helper) {
        WorldPos outside = pos(helper, 0, 0);
        WorldPos inside = pos(helper, 10, 0);
        WorldPos other = pos(helper, 11, 1);
        Asked asked = new Asked();
        WayGraph ways = ways(helper, Map.of(), List.of(), asked);
        WorldPos distant = pos(helper, 200, 0);
        for (List<WorldPos> trip : List.of(List.of(outside, inside), List.of(inside, outside), List.of(inside, other),
                List.of(distant, inside), List.of(inside, distant))) {
            WorldPos from = trip.getFirst();
            WorldPos goal = trip.getLast();
            Faring fare = ways.journey(who(), from, Set.of(goal));
            helper.assertTrue(fare instanceof Faring.Yes, "work-area navigation needs no public graph, got " + fare);
            Journey by = ((Faring.Yes) fare).by();
            helper.assertTrue(by.legs().equals(List.of(Leg.afoot(Set.of(goal)))),
                "the body walks directly to the real destination without an intermediate entrance");
            helper.assertValueEqual(by.walkTicks(), Waypoints.Net.ticks(from.cell(), goal.cell()),
                "the native walk has an estimated cost");
            reckonedAlike(helper, ways, from, goal, by);
        }
        helper.assertTrue(asked.drain().isEmpty(), "entering, leaving and working inside never asks for internal public ways");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ordinaryTrafficStillRoutesAroundTheClosedWorkArea(GameTestHelper helper) {
        WorldPos west = pos(helper, 0, 0);
        WorldPos east = pos(helper, 20, 0);
        Waypoints net = bend(true);
        WayGraph ways = ways(helper, Map.of(new Tract(SORT, west.realm()), net.freeze(1)), List.of(), new Asked());
        Faring fare = ways.journey(who(), west, Set.of(east));
        helper.assertTrue(fare instanceof Faring.Yes, "the ordinary route around the work area remains available");
        Journey by = ((Faring.Yes) fare).by();
        helper.assertTrue(by.walkTicks() > Waypoints.Net.ticks(west.cell(), east.cell()),
            "two outside endpoints keep the detour instead of taking the native shortcut through the site");
        helper.assertTrue(by.legs().stream().flatMap(leg -> leg.to().stream())
            .noneMatch(cell -> BOX.isInside(cell.cell())), "ordinary traffic has no waypoint inside the site");
        reckonedAlike(helper, ways, west, east, by);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWorkAreaDoesNotMakeAnotherRealmReachableOnFoot(GameTestHelper helper) {
        WorldPos inside = pos(helper, 10, 0);
        WorldPos elsewhere = new WorldPos(new Realm.Frame(UUID.randomUUID()), inside.cell());
        Asked asked = new Asked();
        WayGraph ways = ways(helper, Map.of(), List.of(), asked);
        for (List<WorldPos> trip : List.of(List.of(inside, elsewhere), List.of(elsewhere, inside))) {
            helper.assertTrue(!(ways.journey(who(), trip.getFirst(), Set.of(trip.getLast())) instanceof Faring.Yes),
                "identical local coordinates in another realm cannot be reached without a passage");
            helper.assertTrue(!(ways.reckon(who(), trip.getFirst(), Set.of(trip.getLast())) instanceof Faring.Yes),
                "planning cannot invent a native walk across realms either");
        }
        helper.assertTrue(asked.drain().isEmpty(), "no internal public path is requested for an absent passage");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDisconnectedNearestPublicPointDoesNotHideACrossRealmPassage(GameTestHelper helper) {
        WorldPos inside = pos(helper, 10, 0);
        WorldPos publicGround = pos(helper, 0, 0);
        Realm aboard = new Realm.Frame(UUID.randomUUID());
        WorldPos landing = new WorldPos(aboard, new BlockPos(100, 1, 0));
        WorldPos destination = new WorldPos(aboard, new BlockPos(104, 1, 0));
        Waypoints ground = new Waypoints();
        ground.pin(publicGround.cell().asLong(), 0);
        // This ordinary isolated work position is closer to the site than the passage's public landing.
        // It has no link to that landing, so choosing only the nearest point strands both travel directions.
        WorldPos isolated = pos(helper, 4, 0);
        ground.pin(isolated.cell().asLong(), 0);
        ground.closeOff(BOX);
        helper.assertTrue(ground.at(isolated.cell().asLong()) >= 0,
            "the closer disconnected public island remains outside the closure");
        Waypoints ship = new Waypoints();
        both(ship, ship.pin(landing.cell().asLong(), 0), ship.pin(destination.cell().asLong(), 0));
        Hop outward = new Hop(SERVICE, new Stances.Cells(Set.of(publicGround)),
            new Stances.Cells(Set.of(landing)), Fare.flat(20));
        Hop homeward = new Hop(SERVICE, new Stances.Cells(Set.of(landing)),
            new Stances.Cells(Set.of(publicGround)), Fare.flat(20));
        Asked asked = new Asked();
        WayGraph ways = ways(helper, Map.of(new Tract(SORT, publicGround.realm()), ground.freeze(1),
                new Tract(SORT, aboard), ship.freeze(1)),
            List.of(new Crossing(publicGround, landing, outward, SERVICE),
                new Crossing(landing, publicGround, homeward, SERVICE)), asked);
        for (List<WorldPos> trip : List.of(List.of(inside, destination), List.of(destination, inside))) {
            WorldPos from = trip.getFirst();
            WorldPos goal = trip.getLast();
            Faring fare = ways.journey(who(), from, Set.of(goal));
            helper.assertTrue(fare instanceof Faring.Yes, "a real passage connects the work trip across realms, got " + fare);
            Journey by = ((Faring.Yes) fare).by();
            helper.assertValueEqual(by.rideTicks(), 20, "the actual passage is retained instead of a cross-realm native walk");
            helper.assertTrue(by.legs().stream().anyMatch(leg -> leg.hop().isPresent()), "the trip includes its passage");
            helper.assertValueEqual(by.legs().getLast().to(), Set.of(goal), "the journey still ends at the real destination");
            if (from.equals(inside)) {
                helper.assertValueEqual(by.legs().getFirst(), Leg.afoot(Set.of(publicGround)),
                    "leaving the work area walks natively to the ordinary passage network");
            } else {
                helper.assertValueEqual(by.legs().getLast(), Leg.afoot(Set.of(inside)),
                    "after the passage, the final native segment reaches the work itself");
            }
            reckonedAlike(helper, ways, from, goal, by);
        }
        helper.assertTrue(asked.drain().isEmpty(), "the existing passage requires no public search inside the work area");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDistantWorkTripKeepsItsPublicRideAndNativeFinalSegment(GameTestHelper helper) {
        WorldPos inside = pos(helper, 10, 0);
        WorldPos nearbyPublic = pos(helper, 0, 0);
        WorldPos distantPublic = pos(helper, 200, 0);
        Waypoints net = new Waypoints();
        net.pin(nearbyPublic.cell().asLong(), 0);
        net.pin(distantPublic.cell().asLong(), 0);
        net.closeOff(BOX);
        Hop outward = new Hop(SERVICE, new Stances.Cells(Set.of(distantPublic)),
            new Stances.Cells(Set.of(nearbyPublic)), Fare.flat(20));
        Hop homeward = new Hop(SERVICE, new Stances.Cells(Set.of(nearbyPublic)),
            new Stances.Cells(Set.of(distantPublic)), Fare.flat(20));
        Asked asked = new Asked();
        WayGraph ways = ways(helper, Map.of(new Tract(SORT, inside.realm()), net.freeze(1)),
            List.of(new Crossing(distantPublic, nearbyPublic, outward, SERVICE),
                new Crossing(nearbyPublic, distantPublic, homeward, SERVICE)), asked);
        for (List<WorldPos> trip : List.of(List.of(distantPublic, inside), List.of(inside, distantPublic))) {
            WorldPos from = trip.getFirst();
            WorldPos goal = trip.getLast();
            Faring fare = ways.journey(who(), from, Set.of(goal));
            helper.assertTrue(fare instanceof Faring.Yes, "the existing long-distance network serves the work trip, got " + fare);
            Journey by = ((Faring.Yes) fare).by();
            helper.assertValueEqual(by.rideTicks(), 20, "the distant approach preserves the available ride");
            helper.assertTrue(by.legs().stream().anyMatch(leg -> leg.hop().isPresent()), "the native fallback does not replace the public ride");
            if (goal.equals(inside)) {
                helper.assertValueEqual(by.legs().getLast(), Leg.afoot(Set.of(inside)),
                    "the last segment reaches the actual work without selecting an entrance");
            } else {
                helper.assertValueEqual(by.legs().getFirst(), Leg.afoot(Set.of(nearbyPublic)),
                    "the return trip walks natively to the available public network");
            }
            reckonedAlike(helper, ways, from, goal, by);
        }
        helper.assertTrue(asked.drain().isEmpty(), "the long approach does not request routes through the closed work area");
        helper.succeed();
    }

    // Weighed without being laid out, a journey is as long as the one laid out.
    private static void reckonedAlike(GameTestHelper helper, WayGraph ways, WorldPos from, WorldPos goal, Journey by) {
        Faring weighed = ways.reckon(who(), from, Set.of(goal));
        helper.assertTrue(weighed instanceof Faring.Yes yes && yes.by().ticks() == by.ticks(),
            "reckoned, the journey is as long as laid out (" + by.ticks() + " ticks), not " + weighed);
    }

    private static Waypoints bend(boolean closed) {
        Waypoints net = new Waypoints();
        int west = net.admit(cell(0, 0), 0);
        int middle = net.admit(cell(10, 6), 0);
        int east = net.admit(cell(20, 0), 0);
        both(net, west, middle);
        both(net, middle, east);
        if (closed) {
            net.closeOff(BOX);
        }
        net.freeze(1);
        return net;
    }

    private static WayGraph ways(GameTestHelper helper, Map<Tract, Waypoints.Net> nets,
            List<Crossing> crossings, Asked asked) {
        Closures.Closed closed = new Closures.Closed(helper.getLevel().dimension(), BOX);
        return new WayGraph(Map.of(KIND, SORT), nets, crossings,
            Map.of(), asked, new Trodden(), List.of(closed), 0L);
    }

    private static void both(Waypoints net, int one, int other) {
        int ticks = Waypoints.Net.ticks(BlockPos.of(net.cellOf(one)), BlockPos.of(net.cellOf(other)));
        net.link(one, other, ticks, 0);
        net.link(other, one, ticks, 0);
    }

    private static long cell(int x, int z) {
        return new BlockPos(x, 1, z).asLong();
    }

    private static WorldPos pos(GameTestHelper helper, int x, int z) {
        return WorldPos.of(helper.getLevel(), new BlockPos(x, 1, z));
    }

    private static Resident who() {
        return new Resident(UUID.randomUUID(), KIND);
    }
}
