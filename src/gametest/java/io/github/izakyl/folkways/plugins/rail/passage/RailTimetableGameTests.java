package io.github.izakyl.folkways.plugins.rail.passage;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.plugins.rail.domain.Itinerary;
import io.github.izakyl.folkways.plugins.rail.domain.TrainCrew;
import io.github.izakyl.folkways.plugins.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.plugins.rail.domain.TransitSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RailTimetableGameTests {

    private static final String TEMPLATE = "empty";

    private static final long CAPTURED_AT = 1_000L;

    private RailTimetableGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RailTimetableGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aLineThatDoesNotLoopStopsAtItsLastStation(GameTestHelper helper) {
        List<TransitNetwork.Stop> stops = Itinerary.after(List.of(call("A", 100, 20), call("B", 100, 20)),
            false, 1, 20);

        helper.assertTrue(stops.equals(List.of(new TransitNetwork.Stop("B", 120, 140))),
            "having left A on a line that does not loop, only B is still to come, got " + stops);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aLineThatLoopsIsSeenComingBackForItsNextRun(GameTestHelper helper) {
        List<TransitNetwork.Stop> stops = Itinerary.after(List.of(call("A", 100, 20), call("B", 100, 20)),
            true, 1, 20);

        helper.assertTrue(stops.equals(List.of(new TransitNetwork.Stop("B", 120, 140),
                new TransitNetwork.Stop("A", 240, 260), new TransitNetwork.Stop("B", 360, 380),
                new TransitNetwork.Stop("A", 480, 500))),
            "a looping line runs on past its last station into the next lap, got " + stops);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aLegNeverRunIsNotInvented(GameTestHelper helper) {
        List<TransitNetwork.Stop> stops = Itinerary.after(
            List.of(new Itinerary.Call("A", OptionalInt.empty(), OptionalInt.of(20)), call("B", 100, 20)),
            true, 1, 20);

        helper.assertTrue(stops.equals(List.of(new TransitNetwork.Stop("B", 120, 140))),
            "the train has never run B -> A, so the return is not quoted, got " + stops);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aStopHeldForSomethingOtherThanTimeEndsWhatCanBeTold(GameTestHelper helper) {
        List<TransitNetwork.Stop> stops = Itinerary.after(
            List.of(call("A", 100, 20), new Itinerary.Call("B", OptionalInt.of(100), OptionalInt.empty())),
            true, 1, 20);

        helper.assertTrue(stops.equals(List.of(new TransitNetwork.Stop("B", 120, 120))),
            "nobody can say when the train leaves B, so it must be boarded as it arrives, got " + stops);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theTimetableIsReadFromWhenItWasTaken(GameTestHelper helper) {
        Timetable runs = RailLegs.runsOf(CAPTURED_AT, line(List.of("A", "B"), "A",
            new TransitNetwork.Stop("A", 0, 20), new TransitNetwork.Stop("B", 120, 140)));

        helper.assertTrue(runs.equals(new Timetable(List.of("A", "B"),
                new long[] {CAPTURED_AT, CAPTURED_AT + 120}, new long[] {CAPTURED_AT + 20, CAPTURED_AT + 140})),
            "calls are timed from the moment the snapshot was taken, got " + runs);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void runsAreCountedFromTheTrainsNextDeparture(GameTestHelper helper) {
        TransitSnapshot.Line seen = line(List.of("A", "B"), "A",
            new TransitNetwork.Stop("A", 0, 20), new TransitNetwork.Stop("B", 120, 140));
        Timetable runs = RailLegs.runsOf(CAPTURED_AT, new TransitSnapshot.Line(seen.train(), seen.stations(),
            seen.platforms(), seen.footings(), seen.timetable(), seen.currentStation(), seen.freeSeats(),
            seen.vacancies(), seen.conducted(), seen.crew(), 7L));

        helper.assertTrue(runs.lastDeparture("A", "B").equals(OptionalLong.of(7)),
            "leaving A is the train's seventh departure when the snapshot says it leaves next at 7, got "
                + runs.lastDeparture("A", "B"));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aTrainIsBoardedUntilItLeaves(GameTestHelper helper) {
        Timetable runs = new Timetable(List.of("A", "B"), new long[] {0, 100}, new long[] {20, 120});

        helper.assertTrue(runs.arriveBy("A", "B", 20).equals(OptionalLong.of(100)),
            "reaching A as the train leaves still makes it, got " + runs.arriveBy("A", "B", 20));
        helper.assertTrue(runs.arriveBy("A", "B", 21).isEmpty(),
            "the train does not wait for a rider who comes after it leaves");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aRiderWhoMissesOneRunTakesTheNext(GameTestHelper helper) {
        Timetable runs = new Timetable(List.of("A", "B", "A", "B"),
            new long[] {0, 100, 200, 300}, new long[] {20, 120, 220, 320});

        helper.assertTrue(runs.arriveBy("A", "B", 50).equals(OptionalLong.of(300)),
            "missing the first run from A lands on the next, got " + runs.arriveBy("A", "B", 50));
        helper.assertTrue(runs.lastDeparture("A", "B").equals(OptionalLong.of(3)),
            "the last run from A to B is the train's third departure, got " + runs.lastDeparture("A", "B"));
        helper.assertTrue(runs.arriveBy("B", "A", 130).isEmpty(),
            "no B -> A run is left once the last B that has an A after it has gone");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aFareCaughtOnThePlatformHoldsToTheRunItCatches(GameTestHelper helper) {
        Timetable runs = new Timetable(List.of("A", "B", "A", "B"),
            new long[] {0, 100, 200, 300}, new long[] {20, 120, 220, 320});
        UUID train = UUID.randomUUID();

        helper.assertTrue(new RailFare(train, runs, "A", "B").boardingAt(10).equals(
                new RailFare(train, runs, "A", "B", OptionalLong.of(1))),
            "on the platform before the first run leaves, the first run is the one caught");
        helper.assertTrue(new RailFare(train, runs, "A", "B").boardingAt(50).equals(
                new RailFare(train, runs, "A", "B", OptionalLong.of(3))),
            "coming after the first run has left, the next is the one caught");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aRideThroughAStopIsBoardedWhereItGotOnForTheRunItCaught(GameTestHelper helper) {
        Timetable runs = new Timetable(List.of("A", "B", "C", "B", "A"),
            new long[] {0, 100, 200, 300, 400}, new long[] {20, 120, 220, 320, 420});
        UUID train = UUID.randomUUID();
        ResourceLocation service = RailLegs.serviceOf(train);
        Stances.Cells at = new Stances.Cells(Set.of(WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO))));
        Hop ab = new Hop(service, at, at, new RailFare(train, runs, "A", "B").boardingAt(10));
        Hop bc = new Hop(service, at, at, new RailFare(train, runs, "B", "C").boardingAt(100));
        Hop cb = new Hop(service, at, at, new RailFare(train, runs, "C", "B").boardingAt(200));
        Hop ba = new Hop(service, at, at, new RailFare(train, runs, "B", "A").boardingAt(300));
        RailPassage passage = new RailPassage();

        helper.assertTrue(passage.through(ab, bc), "staying aboard at B carries on to C");
        helper.assertTrue(passage.joined(ab, bc).fare().equals(new RailFare(train, runs, "A", "C", OptionalLong.of(1))),
            "the ride is boarded at A for the run caught there and left at C, got " + passage.joined(ab, bc).fare());
        helper.assertTrue(!passage.through(ab, ba), "a ride that would bring the rider back to A is not one ride");
        helper.assertTrue(!passage.through(ab, cb), "a ride does not carry on from a stop it does not reach");
        helper.succeed();
    }

    private static Itinerary.Call call(String station, int ride, int stay) {
        return new Itinerary.Call(station, OptionalInt.of(ride), OptionalInt.of(stay));
    }

    private static TransitSnapshot.Line line(List<String> stations, String here, TransitNetwork.Stop... stops) {
        Map<String, WorldPos> platforms = new LinkedHashMap<>();
        Map<String, Stances.Cells> footings = new LinkedHashMap<>();
        int apart = 0;
        for (String station : stations) {
            if (platforms.containsKey(station)) {
                continue;
            }
            WorldPos where = at(apart += 20);
            platforms.put(station, where);
            footings.put(station, new Stances.Cells(Set.of(where)));
        }
        return new TransitSnapshot.Line(UUID.randomUUID(), stations, platforms, footings, List.of(stops),
            Optional.ofNullable(here), List.of(), List.of(), true, new TrainCrew(List.of("driver"), 0, 1));
    }

    private static WorldPos at(int x) {
        return WorldPos.of(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
            Level.OVERWORLD.location()), new BlockPos(x, 64, 0));
    }
}
