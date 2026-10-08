package io.github.izakyl.folkways.core.engine.travel;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.passage.Fare;
import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.passage.Passage;
import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Stances;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
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
        Resident who = new Resident(mob.getUUID(), ResourceLocation.parse("folkways:resident"));
        AtomicReference<Ways> published = new AtomicReference<>(answer(Faring.LATER));
        Trip trip = Trip.to(published::get, who, Walking.INSTANCE, mob, Set.of(goal), 1, LOST).orElseThrow();
        trip.setOut(helper.getLevel(), mob, Set.of(goal));
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
    public static void pendingTripCanBeDeniedWithoutWalking(GameTestHelper helper) {
        Mob mob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        WorldPos goal = WorldSpaces.at(mob).at(helper.absolutePos(new BlockPos(7, 2, 1)));
        Resident who = new Resident(mob.getUUID(), ResourceLocation.parse("folkways:resident"));
        AtomicReference<Ways> published = new AtomicReference<>(answer(Faring.LATER));
        Trip trip = Trip.to(published::get, who, Walking.INSTANCE, mob, Set.of(goal), 1, LOST).orElseThrow();
        trip.setOut(helper.getLevel(), mob, Set.of(goal));
        published.set(answer(Faring.NO));
        helper.assertTrue(trip.step(helper.getLevel(), mob, Set.of(goal)) instanceof Going.Failed failed
            && failed.why() == LOST, "a resolved denial must fail with the original refusal");
        helper.assertTrue(mob.getNavigation().isDone(), "denied travel must not fall back to walking");
        helper.assertTrue(Trip.to(published::get, who, Walking.INSTANCE, mob, Set.of(goal), 1, LOST).isEmpty(),
            "an already known denial must not start a trip");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pendingTripCanResolveToWalking(GameTestHelper helper) {
        Mob mob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        WorldPos goal = WorldSpaces.at(mob);
        Resident who = new Resident(mob.getUUID(), ResourceLocation.parse("folkways:resident"));
        AtomicReference<Ways> published = new AtomicReference<>(answer(Faring.LATER));
        Trip trip = Trip.to(published::get, who, Walking.INSTANCE, mob, Set.of(goal), 1, LOST).orElseThrow();
        trip.setOut(helper.getLevel(), mob, Set.of(goal));
        published.set(answer(Faring.yes(Journey.afoot(0, Set.of(goal)))));
        helper.assertTrue(trip.step(helper.getLevel(), mob, Set.of(goal)) instanceof Going.Arrived,
            "a confirmed walking route must still complete normally");
        helper.succeed();
    }

    private static Ways answer(Faring result) {
        return new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) { return result; }
            public Faring anyoneReaches(Set<WorldPos> goals) { return result; }
        };
    }
}
