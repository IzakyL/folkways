package io.github.izakyl.folkways.core.travel.internal;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.resident.api.Locomotion;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.resident.api.Search;
import io.github.izakyl.folkways.core.travel.api.Fare;
import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.travel.api.Passage;
import io.github.izakyl.folkways.core.travel.api.TrafficFeedback;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.Closures;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.plugins.person.walk.Walking;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TripGameTests {

    private static final RefusalKind LOST = () -> "folkways.test.no_way";

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TripGameTests.class);
    }

    private static final ResourceLocation SERVICE = ResourceLocation.parse("folkways:test_trip");
    private static final AtomicInteger BOARDED = new AtomicInteger();
    private static final AtomicInteger RELEASED = new AtomicInteger();

    @SubscribeEvent
    public static void declare(Declaring event) {
        event.passage(new Passage() {
            public ResourceLocation id() { return SERVICE; }
            public List<Hop> hopsIn(Colony colony) { return List.of(); }
            public Conveyance aboard(Hop hop) {
                return new Conveyance() {
                    public void setOut(ServerLevel level, Mob body, Set<WorldPos> to) { BOARDED.incrementAndGet(); }
                    public Going step(ServerLevel level, Mob body, Set<WorldPos> to) { return Going.UNDERWAY; }
                    public void release(ServerLevel level, Mob body) { RELEASED.incrementAndGet(); }
                };
            }
        });
    }

    @GameTest(template = "empty")
    public static void ridingOnThroughAStopIsOneRideFromTheFirstBoardingToTheLastLanding(GameTestHelper helper) {
        WorldPos a = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
        WorldPos b = a.at(a.cell().offset(10, 0, 0));
        WorldPos c = a.at(a.cell().offset(20, 0, 0));
        Hop first = new Hop(SERVICE, new Stances.Cells(Set.of(a)), new Stances.Cells(Set.of(b)), Fare.flat(20));
        Hop next = new Hop(SERVICE, new Stances.Cells(Set.of(b)), new Stances.Cells(Set.of(c)), Fare.flat(30));

        List<Leg> folded = Trip.ridden(List.of(Leg.aboard(first), Leg.through(b, b), Leg.aboard(next)));

        helper.assertTrue(folded.size() == 1, "two rides back to back on one service are one, got " + folded);
        Hop ride = folded.getFirst().hop().orElseThrow();
        helper.assertTrue(ride.boarding().equals(first.boarding()) && ride.landing().equals(next.landing()),
            "the ride is boarded where the first got on and left where the next gets off");
        helper.assertTrue(ride.fare().arriveBy(100).equals(OptionalLong.of(150)),
            "the ride takes as long as both, got " + ride.fare().arriveBy(100));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pendingTripUsesNewlyPublishedRideAndReleasesIt(GameTestHelper helper) {
        Mob mob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        WorldPos from = WorldSpaces.at(mob);
        WorldPos goal = from.at(from.cell().offset(20, 0, 0));
        BOARDED.set(0);
        RELEASED.set(0);
        AtomicReference<Ways> published = new AtomicReference<>(answer(Faring.LATER));
        Trip trip = pending(helper, mob, goal, published);
        for (int tick = 0; tick < 3; tick++) {
            helper.assertTrue(trip.step(helper.getLevel(), mob, Set.of(goal)) instanceof Going.Underway,
                "unknown travel must wait, not fail");
        }
        helper.assertTrue(trip.doing().orElseThrow().what().equals(Doings.WAITING), "unknown travel is waiting");
        helper.assertTrue(mob.getNavigation().isDone() && BOARDED.get() == 0,
            "pending travel must not start walking or board before a route exists");
        Hop hop = new Hop(SERVICE, new Stances.Cells(Set.of(from)), new Stances.Cells(Set.of(goal)), Fare.flat(20));
        published.set(answer(Faring.yes(new Journey(0, 20, List.of(Leg.aboard(hop))))));
        trip.step(helper.getLevel(), mob, Set.of(goal));
        trip.step(helper.getLevel(), mob, Set.of(goal));
        helper.assertTrue(BOARDED.get() == 1, "a new published snapshot must start the ride exactly once");
        trip.release(helper.getLevel(), mob);
        helper.assertTrue(RELEASED.get() == 1, "cancelling the waiting trip must release its resolved ride");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pendingTripIsDeniedWithoutWalkingOrResolvesToWalking(GameTestHelper helper) {
        for (boolean denied : new boolean[] {true, false}) {
            Mob mob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
            WorldPos goal = denied ? WorldSpaces.at(mob).at(helper.absolutePos(new BlockPos(7, 2, 1)))
                : WorldSpaces.at(mob);
            AtomicReference<Ways> published = new AtomicReference<>(answer(Faring.LATER));
            Trip trip = pending(helper, mob, goal, published);
            if (denied) {
                published.set(answer(Faring.NO));
                helper.assertTrue(trip.step(helper.getLevel(), mob, Set.of(goal)) instanceof Going.Failed failed
                    && failed.why() == LOST, "a resolved denial must fail with the original refusal");
                helper.assertTrue(mob.getNavigation().isDone(), "denied travel must not fall back to walking");
                helper.assertTrue(Trip.to(published::get, resident(mob), Walking.INSTANCE, mob, Set.of(goal), 1, LOST)
                    .isEmpty(), "an already known denial must not start a trip");
            } else {
                published.set(answer(Faring.yes(Journey.afoot(0, Set.of(goal)))));
                helper.assertTrue(trip.step(helper.getLevel(), mob, Set.of(goal)) instanceof Going.Arrived,
                    "a confirmed walking route must still complete normally");
            }
            mob.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFailedNativeWalkOutOfClosedGroundDoesNotTeleportTheBody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob mob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        WorldPos from = WorldSpaces.at(mob);
        WorldPos outside = from.at(from.cell().offset(5, 0, 0));
        WorldPos goal = from.at(from.cell().offset(8, 0, 0));
        var originalPosition = mob.position();
        AtomicInteger refused = new AtomicInteger();
        Runnable reopen = Closures.close(level, BoundingBox.fromCorners(mob.blockPosition().offset(-1, -1, -1),
            mob.blockPosition().offset(1, 1, 1)));
        try {
            Locomotion failingWalk = new Locomotion() {
                public ResourceLocation id() { return Walking.INSTANCE.id(); }
                public NodeEvaluator newEvaluator(Search search) { return Walking.INSTANCE.newEvaluator(search); }
                public void fit(Mob body) { Walking.INSTANCE.fit(body); }
                public Conveyance along(double speed, RefusalKind lost) {
                    return new Conveyance() {
                        public void setOut(ServerLevel at, Mob body, Set<WorldPos> to) { }
                        public Going step(ServerLevel at, Mob body, Set<WorldPos> to) { return new Going.Failed(lost); }
                        public void release(ServerLevel at, Mob body) { }
                    };
                }
            };
            Journey route = new Journey(40, 0, List.of(Leg.afoot(from, Set.of(outside)), Leg.afoot(Set.of(goal))));
            Trip trip = Trip.to(() -> answer(Faring.yes(route)), resident(mob), failingWalk, mob, Set.of(goal), 1, LOST, Urgency.WALKING, new io.github.izakyl.folkways.core.travel.api.TrafficFeedback() {
                    public void wrongEdge(ServerLevel world, WorldPos start, WorldPos end) {
                        refused.incrementAndGet();
                    }

                    public void walkedEdge(ServerLevel world, WorldPos start, WorldPos end, int ticks) {
                    }
                })
                .orElseThrow();
            trip.setOut(level, mob, Set.of(goal));
            helper.assertTrue(trip.step(level, mob, Set.of(goal)) instanceof Going.Failed failed && failed.why() == LOST,
                "an unreachable native segment preserves its original failure");
            helper.assertValueEqual(mob.position(), originalPosition,
                "a failed first native segment never moves the body to an outside waypoint");
            helper.assertTrue(refused.get() == 1, "one failed segment reports through the injected feedback");
            trip.release(level, mob);
        } finally {
            reopen.run();
        }
        helper.succeed();
    }

    // A walking trip set out while its ways cannot say yet.
    private static Trip pending(GameTestHelper helper, Mob mob, WorldPos goal, AtomicReference<Ways> published) {
        Trip trip = Trip.to(published::get, resident(mob), Walking.INSTANCE, mob, Set.of(goal), 1, LOST).orElseThrow();
        trip.setOut(helper.getLevel(), mob, Set.of(goal));
        return trip;
    }

    private static Resident resident(Mob mob) {
        return new Resident(mob.getUUID(), ResourceLocation.parse("folkways:resident"));
    }

    private static Ways answer(Faring result) {
        return new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) { return result; }
            public Faring anyoneReaches(Set<WorldPos> goals) { return result; }
        };
    }
}
