package io.github.izakyl.folkways.create.transport;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCreateMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeatedConveyanceGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SeatedConveyanceGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aFullVehicleKeepsTheRiderWaitingUntilASeatOpens(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        StubRide ride = fixture.ride();
        ride.full = true;
        for (int tick = 0; tick < 5; tick++) {
            helper.assertTrue(ride.step(helper.getLevel(), fixture.body(), fixture.landing()) == Going.UNDERWAY,
                "a full vehicle remains a service the passenger can wait for");
        }
        helper.assertTrue(ride.attempts == 0, "a full vehicle must not try to seat a passenger");
        ride.full = false;
        step(fixture, helper.getLevel(), 3);
        helper.assertTrue(fixture.body().getVehicle() == ride.vehicle,
            "the waiting passenger boards when a seat opens");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aFailedBoardingAttemptCanBeRetried(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        StubRide ride = fixture.ride();
        ride.reject = true;
        step(fixture, helper.getLevel(), 3);
        helper.assertTrue(ride.attempts == 1 && !fixture.body().isPassenger(),
            "a rejected boarding attempt must leave the passenger waiting");
        ride.reject = false;
        step(fixture, helper.getLevel(), 3);
        helper.assertTrue(ride.attempts == 2 && fixture.body().getVehicle() == ride.vehicle,
            "a failed attempt must not mark the passenger as already aboard");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void leavingTheStopResetsAnUnfinishedBoarding(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        StubRide ride = fixture.ride();
        step(fixture, helper.getLevel(), 2);
        ride.stopped = false;
        step(fixture, helper.getLevel(), 1);
        ride.stopped = true;
        step(fixture, helper.getLevel(), 2);
        helper.assertTrue(ride.attempts == 0,
            "returning to the stop must allow a complete new boarding interval");
        step(fixture, helper.getLevel(), 1);
        helper.assertTrue(fixture.body().isPassenger(), "boarding completes after the new interval");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void arrivalSkipsTheCloserLandingWhenItIsBlocked(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        ServerLevel level = helper.getLevel();
        BlockPos blocked = fixture.landing().iterator().next().cell();
        BlockPos clear = blocked.offset(0, 0, 2);
        level.setBlockAndUpdate(blocked.above(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(clear.below(), Blocks.STONE.defaultBlockState());
        Stances.Cells landing = new Stances.Cells(Set.of(WorldPos.of(level, blocked), WorldPos.of(level, clear)));
        Stances.Cells boarding = new Stances.Cells(Set.of(WorldPos.of(level, fixture.body().blockPosition())));
        StubRide ride = new StubRide(boarding, landing, fixture.ride().vehicle);
        for (int tick = 0; tick < 3; tick++) {
            ride.step(level, fixture.body(), fixture.landing());
        }
        ride.destination = true;
        ride.step(level, fixture.body(), fixture.landing());
        helper.assertTrue(!fixture.body().isPassenger() && fixture.body().blockPosition().equals(clear),
            "a blocked landing must be skipped even when it is closest to the onward goal");
        helper.assertTrue(ride.step(level, fixture.body(), fixture.landing()) == Going.ARRIVED,
            "safe disembarkation completes the ride");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void arrivalWaitsForASafeLandingAndCancellationStillReleasesTheSeat(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        StubRide ride = fixture.ride();
        ServerLevel level = helper.getLevel();
        BlockPos landing = fixture.landing().iterator().next().cell();
        level.setBlockAndUpdate(landing.above(), Blocks.STONE.defaultBlockState());
        step(fixture, level, 3);
        ride.destination = true;
        step(fixture, level, 1);
        helper.assertTrue(fixture.body().isPassenger() && ride.disembarks == 0,
            "the rider must stay seated while the landing has no headroom");
        level.setBlockAndUpdate(landing.above(), Blocks.AIR.defaultBlockState());
        ride.serviceAvailable = false;
        ride.releaseAllowed = true;
        ride.release(level, fixture.body());
        helper.assertTrue(!fixture.body().isPassenger() && fixture.body().blockPosition().equals(landing),
            "cancellation may safely set down a rider even after the service stops running");
        ride.release(level, fixture.body());
        helper.assertTrue(ride.disembarks == 1, "releasing a ride twice must not disembark twice");
        helper.succeed();
    }

    private static Fixture fixture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos boarding = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos landing = helper.absolutePos(new BlockPos(3, 1, 1));
        level.setBlockAndUpdate(boarding.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(landing.below(), Blocks.STONE.defaultBlockState());
        Mob body = PersonBody.RESIDENT.get().create(level);
        body.setPos(boarding.getX() + 0.5D, boarding.getY(), boarding.getZ() + 0.5D);
        Entity vehicle = EntityType.MINECART.create(level);
        vehicle.setPos(boarding.getX() + 0.5D, boarding.getY(), boarding.getZ() + 0.5D);
        Stances.Cells on = new Stances.Cells(Set.of(WorldPos.of(level, boarding)));
        Stances.Cells off = new Stances.Cells(Set.of(WorldPos.of(level, landing)));
        return new Fixture(new StubRide(on, off, vehicle), body, off.cells());
    }

    private static void step(Fixture fixture, ServerLevel level, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            fixture.ride().step(level, fixture.body(), fixture.landing());
        }
    }

    private record Fixture(StubRide ride, Mob body, Set<WorldPos> landing) {
    }

    private static final class StubRide extends SeatedConveyance<Integer> {
        private final Entity vehicle;
        private boolean full;
        private boolean reject;
        private boolean stopped = true;
        private boolean destination;
        private boolean serviceAvailable = true;
        private boolean releaseAllowed;
        private int attempts;
        private int disembarks;

        private StubRide(Stances.Cells boarding, Stances.Cells landing, Entity vehicle) {
            super(boarding, landing, () -> 3, ResourceLocation.fromNamespaceAndPath("folkways", "boarding"),
                ResourceLocation.fromNamespaceAndPath("folkways", "riding"), () -> "test.seat_gone");
            this.vehicle = vehicle;
        }

        @Override
        protected boolean available(ServerLevel level) { return serviceAvailable; }

        @Override
        protected Optional<RefusalKind> boardingFailure(ServerLevel level) { return Optional.empty(); }

        @Override
        protected Going awaitVehicle(ServerLevel level) { return stopped ? Going.ARRIVED : Going.UNDERWAY; }

        @Override
        protected Optional<Integer> freeSeat(ServerLevel level) { return full ? Optional.empty() : Optional.of(7); }

        @Override
        protected boolean occupied(ServerLevel level, Integer seat) { return full || vehicle.isVehicle(); }

        @Override
        protected boolean board(ServerLevel level, Integer seat, Mob body) {
            attempts++;
            return !reject && body.startRiding(vehicle, true);
        }

        @Override
        protected boolean atDestination(ServerLevel level) { return destination; }

        @Override
        protected Optional<Integer> seatOf(ServerLevel level, Mob body) {
            return body.getVehicle() == vehicle ? Optional.of(7) : Optional.empty();
        }

        @Override
        protected void disembark(ServerLevel level, Integer seat, Mob body) {
            disembarks++;
            body.stopRiding();
        }

        @Override
        protected boolean canRelease(ServerLevel level) { return releaseAllowed || available(level); }
    }
}
