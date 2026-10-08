package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Journey;
import io.github.izakyl.folkways.core.engine.travel.Leg;
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

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ClosureGameTests {

    private static final ResourceLocation KIND = ResourceLocation.fromNamespaceAndPath("folkways", "resident");

    private static final Sort SORT = new Sort(ResourceLocation.fromNamespaceAndPath("folkways", "walking"),
        new Bulk(1, 2));

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
    public static void aGoalInClosedGroundIsComeToByItsDoorAndWalkedTo(GameTestHelper helper) {
        WorldPos start = pos(helper, 0, 0);
        WorldPos door = pos(helper, 7, 0);
        WorldPos goal = pos(helper, 10, 0);
        WayGraph ways = ways(helper, List.of(door), start, door);
        Faring fare = ways.journey(who(), start, Set.of(goal));
        helper.assertTrue(fare instanceof Faring.Yes, "the goal is come to, not " + fare);
        Journey by = ((Faring.Yes) fare).by();
        Leg last = by.legs().getLast();
        Leg before = by.legs().get(by.legs().size() - 2);
        helper.assertTrue(last.to().equals(Set.of(goal)) && last.from().isEmpty(),
            "the last of it is walked in from the door, off the ways");
        helper.assertTrue(before.to().equals(Set.of(door)), "the ways end at the door");
        helper.assertTrue(by.walkTicks() >= Waypoints.Net.ticks(start.cell(), door.cell())
            + Waypoints.Net.ticks(door.cell(), goal.cell()), "the walk in is counted");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aBodyInClosedGroundWalksOutByADoor(GameTestHelper helper) {
        WorldPos inside = pos(helper, 11, 1);
        WorldPos unlaid = pos(helper, 13, 0);
        WorldPos door = pos(helper, 7, 0);
        WorldPos goal = pos(helper, 0, 0);
        WayGraph ways = ways(helper, List.of(unlaid, door), goal, door);
        Faring fare = ways.journey(who(), inside, Set.of(goal));
        helper.assertTrue(fare instanceof Faring.Yes, "the way out is found, not " + fare);
        Leg first = ((Faring.Yes) fare).by().legs().getFirst();
        helper.assertTrue(first.to().equals(Set.of(door)) && first.from().isEmpty(),
            "it walks out to the nearest door with a way on");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void placesInTheSameClosedGroundAreWalkedBetween(GameTestHelper helper) {
        WorldPos from = pos(helper, 9, -1);
        WorldPos to = pos(helper, 12, 2);
        WayGraph ways = ways(helper, List.of(pos(helper, 7, 0)), pos(helper, 0, 0), pos(helper, 7, 0));
        Faring fare = ways.journey(who(), from, Set.of(to));
        helper.assertTrue(fare instanceof Faring.Yes yes && yes.by().legs().size() == 1
            && yes.by().legs().getFirst().to().equals(Set.of(to)), "it is one walk, off the ways");
        helper.succeed();
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

    // A graph whose ways join `from` and `to`, over ground closed by BOX with the doors given.
    private static WayGraph ways(GameTestHelper helper, List<WorldPos> doors, WorldPos from, WorldPos to) {
        Waypoints net = new Waypoints();
        both(net, net.pin(from.cell().asLong(), 0), net.pin(to.cell().asLong(), 0));
        Closures.Closed closed = new Closures.Closed(helper.getLevel().dimension(), BOX, doors);
        return new WayGraph(Map.of(KIND, SORT), Map.of(new Tract(SORT, Realm.of(helper.getLevel())), net.freeze(0)),
            List.of(), Map.of(), new Asked(), new Trodden(), List.of(closed), 0L);
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
