package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCreateMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ElevatorControllerGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ElevatorControllerGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void reservationsBatchByDestinationAndRespectCapacity(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        fixture.controller.request(first, 0, 10, 0);
        fixture.controller.request(second, 0, 10, 0);
        fixture.controller.request(third, 0, 20, 0);
        fixture.tick(1);
        helper.assertTrue(fixture.controller.status(first) == ElevatorController.Status.BOARDING
                && fixture.controller.status(second) == ElevatorController.Status.BOARDING
                && fixture.controller.status(third) == ElevatorController.Status.WAITING,
            "same-direction passengers share a batch while another destination waits");
        helper.assertTrue(!fixture.controller.place(first).equals(fixture.controller.place(second)),
            "each accepted passenger reserves a different standing place");
        long estimate = fixture.controller.estimate(0, 20, 2);
        helper.assertTrue(estimate >= fixture.controller.estimate(0, 20, 1)
                && fixture.network.targets.stream().allMatch(y -> y == 0),
            "pure monotonic prediction does not dispatch the elevator");
        fixture.network.occupants.put(fixture.controller.place(first).orElseThrow(), UUID.randomUUID());
        fixture.tick(2);
        helper.assertTrue(fixture.controller.place(first).isEmpty() || fixture.controller.place(second).isEmpty(),
            "a player taking a reserved standing place cannot leave both reservations active");
        fixture.controller.cancel(first);
        fixture.controller.cancel(second);
        fixture.tick(3);
        helper.assertTrue(fixture.controller.status(third) == ElevatorController.Status.BOARDING,
            "cancelling the first batch releases its standing places for the next destination");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void departuresWaitForBoardingAndEveryPassengerToAlight(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Mob body = mob(helper);
        UUID rider = body.getUUID();
        fixture.controller.request(rider, 0, 10, 0);
        fixture.tick(1);
        helper.assertTrue(fixture.controller.board(helper.getLevel().getServer(), rider, body),
            "the reserved passenger boards at the pickup floor");
        fixture.tick(2);
        helper.assertTrue(fixture.network.targets.stream().allMatch(y -> y == 0),
            "boarding remains open for the dwell interval after the first passenger walks aboard");
        UUID next = UUID.randomUUID();
        fixture.controller.request(next, 20, 0, 2);
        fixture.tick(40);
        helper.assertTrue(fixture.network.targets.getLast() == 10,
            "later calls cannot replace an aboard passenger's destination");
        fixture.network.floor = 10;
        fixture.tick(41);
        helper.assertTrue(fixture.controller.status(rider) == ElevatorController.Status.ALIGHTING,
            "arrival waits for a safe actual disembarkation");
        fixture.tick(60);
        helper.assertTrue(fixture.network.targets.getLast() == 10,
            "the next pickup cannot start while an arriving passenger remains aboard");
        fixture.controller.alight(helper.getLevel().getServer(), rider, body);
        fixture.tick(61);
        helper.assertTrue(fixture.network.targets.getLast() == 20,
            "the next pickup starts once all passengers have alighted");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void leasesOutagesAndRetirementDoNotHoldWaitingPassengersForever(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        UUID rider = UUID.randomUUID();
        fixture.controller.request(rider, 0, 10, 0);
        fixture.tick(201);
        helper.assertTrue(fixture.controller.status(rider) == ElevatorController.Status.FAILED,
            "a passenger leaving its platform loses its request and reservation");
        fixture.controller.request(rider, 0, 10, 201);
        fixture.network.online = false;
        fixture.tick(202);
        fixture.controller.touch(rider, 402);
        fixture.tick(403);
        helper.assertTrue(fixture.controller.status(rider) == ElevatorController.Status.FAILED,
            "an unloaded elevator eventually fails even a passenger still waiting at its platform");
        fixture.network.online = true;
        fixture.tick(404);
        fixture.controller.request(rider, 0, 10, 404);
        fixture.controller.retire();
        helper.assertTrue(fixture.controller.retired() && fixture.controller.idle()
                && !fixture.controller.request(UUID.randomUUID(), 0, 10, 404),
            "retirement clears waiting work and rejects new requests");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void cancellationDuringTravelWaitsForActualWalkOutWithoutTeleporting(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Mob body = mob(helper);
        BlockPos start = helper.absolutePos(new BlockPos(3, 1, 3));
        body.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        try {
            UUID rider = body.getUUID();
            fixture.controller.request(rider, 0, 10, 0);
            fixture.tick(1);
            helper.assertTrue(fixture.controller.board(helper.getLevel().getServer(), rider, body), "the rider has really walked aboard");
            fixture.tick(40);
            fixture.network.floor = 5;
            fixture.network.stopped = false;
            fixture.controller.cancel(rider);
            fixture.tick(41);
            helper.assertTrue(fixture.network.occupants.containsValue(rider)
                    && fixture.network.exitAttempts == 0,
                "cancelling between landings cannot walk out or release a cabin place");
            fixture.network.floor = 10;
            fixture.network.stopped = true;
            fixture.network.exitComplete = false;
            fixture.tick(42);
            helper.assertTrue(fixture.network.occupants.containsValue(rider)
                    && fixture.controller.status(rider) != ElevatorController.Status.FINISHED
                    && fixture.network.exitAttempts > 0,
                "a cancelled rider remains counted while its ordinary walk out is incomplete");
            helper.assertTrue(body.blockPosition().equals(start), "waiting for the walk out never teleports the body");
            fixture.network.exitComplete = true;
            fixture.tick(43);
            helper.assertTrue(!fixture.network.occupants.containsValue(rider)
                    && fixture.controller.status(rider) == ElevatorController.Status.FINISHED,
                "only confirmed walking out releases the cancelled rider's place");
            helper.assertTrue(body.blockPosition().equals(start), "the controller never relocates the body even when walking completes");
        } finally { body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void walkingInDoesNotLoseItsPlaceOrAllowPrematureDeparture(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Mob body = mob(helper);
        try {
            UUID rider = body.getUUID();
            fixture.controller.request(rider, 0, 10, 0);
            fixture.tick(1);
            int place = fixture.controller.place(rider).orElseThrow();
            fixture.network.boardingComplete = false;
            helper.assertFalse(fixture.controller.board(helper.getLevel().getServer(), rider, body),
                "initiating a walk into the cabin is not completed boarding");
            fixture.controller.touch(rider, 40);
            fixture.tick(40);
            helper.assertTrue(fixture.controller.place(rider).filter(held -> held == place).isPresent()
                    && fixture.controller.status(rider) == ElevatorController.Status.BOARDING
                    && fixture.network.targets.stream().allMatch(y -> y == 0),
                "an active walk keeps its reserved place and the cabin at its pickup");
            fixture.network.boardingComplete = true;
            helper.assertTrue(fixture.controller.board(helper.getLevel().getServer(), rider, body),
                "the same reserved place boards once walking is actually complete");
            fixture.tick(60);
            helper.assertTrue(fixture.network.targets.getLast() == 10, "the boarded rider can now depart");
        } finally { body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void incompleteWalkOutBlocksNextPickupUntilTheCabinIsActuallyFree(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Mob body = mob(helper);
        try {
            UUID rider = body.getUUID();
            fixture.controller.request(rider, 0, 10, 0);
            fixture.tick(1);
            fixture.controller.board(helper.getLevel().getServer(), rider, body);
            fixture.tick(40);
            fixture.controller.request(UUID.randomUUID(), 20, 0, 40);
            fixture.network.floor = 10;
            fixture.tick(41);
            fixture.network.exitComplete = false;
            fixture.controller.alight(helper.getLevel().getServer(), rider, body);
            fixture.tick(60);
            helper.assertTrue(fixture.controller.status(rider) == ElevatorController.Status.ALIGHTING
                    && fixture.network.occupants.containsValue(rider)
                    && fixture.network.targets.getLast() == 10,
                "a partial walk out cannot finish the trip or send the cabin to its next pickup");
            fixture.network.exitComplete = true;
            fixture.controller.alight(helper.getLevel().getServer(), rider, body);
            fixture.tick(61);
            helper.assertTrue(fixture.controller.status(rider) == ElevatorController.Status.FINISHED
                    && fixture.network.targets.getLast() == 20,
                "the next pickup starts only after walking clear is confirmed");
        } finally { body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aLatePassengerCannotHoldAnAlreadyBoardedBatchForever(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Mob body = mob(helper);
        try {
            UUID rider = body.getUUID();
            UUID missing = UUID.randomUUID();
            fixture.controller.request(rider, 0, 10, 0);
            fixture.controller.request(missing, 0, 10, 0);
            fixture.tick(1);
            fixture.controller.board(helper.getLevel().getServer(), rider, body);
            fixture.tick(201);
            helper.assertTrue(fixture.controller.status(missing) == ElevatorController.Status.FAILED
                    && fixture.controller.place(missing).isEmpty(), "a passenger that never arrives loses its lease");
            helper.assertTrue(fixture.network.occupants.containsValue(rider)
                    && fixture.network.targets.getLast() == 10, "the actual cabin occupant continues to its destination");
        } finally { body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void cancellingWhileWalkingInMustClearTheDoorBeforeAnAboardCompanionDeparts(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Mob walking = mob(helper);
        Mob aboard = mob(helper);
        try {
            fixture.controller.request(walking.getUUID(), 0, 10, 0);
            fixture.controller.request(aboard.getUUID(), 0, 10, 0);
            fixture.tick(1);
            fixture.network.boardingComplete = false;
            fixture.controller.board(helper.getLevel().getServer(), walking.getUUID(), walking);
            fixture.network.boardingComplete = true;
            helper.assertTrue(fixture.controller.board(helper.getLevel().getServer(), aboard.getUUID(), aboard),
                "the companion is actually standing in its cabin place");
            fixture.network.exitComplete = false;
            fixture.controller.cancel(walking.getUUID());
            fixture.tick(40);
            helper.assertTrue(fixture.network.entering.containsValue(walking.getUUID())
                    && fixture.network.occupants.containsValue(aboard.getUUID())
                    && fixture.network.targets.stream().allMatch(y -> y == 0),
                "the cabin cannot carry away a companion while the cancelled walker still occupies its doorway");
            fixture.network.exitComplete = true;
            fixture.tick(60);
            helper.assertTrue(!fixture.network.entering.containsValue(walking.getUUID())
                    && fixture.controller.place(walking.getUUID()).isEmpty()
                    && fixture.network.targets.getLast() == 10,
                "confirmation that the cancelled walker is clear releases its place and lets the companion depart");
        } finally { walking.discard(); aboard.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void forecastsShareAvailableCapacityButAFullOrMissedTripNeedsAnotherJourney(GameTestHelper helper) {
        var open = new ElevatorForecast(0, 0, 0.5, 100, 2,
            List.of(new ElevatorForecast.Trip(0, 10, 1, false, true)));
        var full = new ElevatorForecast(0, 0, 0.5, 100, 2,
            List.of(new ElevatorForecast.Trip(0, 10, 2, false, true)));
        long shared = open.arrive(0, 10, 1);
        helper.assertValueEqual(open.arrive(0, 10, 2), shared,
            "later arrivals that fit the same still-open batch share its actual arrival time");
        helper.assertTrue(full.arrive(0, 10, 1) > shared,
            "a full batch requires a later return and another trip");
        // Pickup includes 20 ticks of travel overhead and the 100-tick boarding window.
        helper.assertValueEqual(open.arrive(0, 10, 120), shared,
            "a passenger present by the forecast departure can still join");
        helper.assertTrue(open.arrive(0, 10, 121) > shared,
            "a passenger reaching the pickup after departure must wait for the next journey");
        long previous = -1;
        for (long arrival : List.of(0L, 1L, 60L, 120L, 121L, 260L, 600L)) {
            long forecast = open.arrive(0, 10, arrival);
            helper.assertTrue(forecast >= previous, "forecast arrival obeys FIFO across the missed-pickup boundary");
            previous = forecast;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void forecastingIsAnImmutableObservationAndNeverDispatchesOrReserves(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        UUID rider = UUID.randomUUID();
        fixture.controller.request(rider, 0, 10, 0);
        fixture.tick(1);
        var targets = List.copyOf(fixture.network.targets);
        var place = fixture.controller.place(rider);
        var status = fixture.controller.status(rider);
        var snapshot = fixture.controller.forecast();
        long first = snapshot.arrive(0, 10, 2);
        for (int i = 0; i < 10; i++) {
            fixture.controller.estimate(0, 10, i + 2);
            fixture.controller.estimate(20, 0, i + 2);
        }
        helper.assertTrue(fixture.network.targets.equals(targets)
                && fixture.controller.place(rider).equals(place)
                && fixture.controller.status(rider) == status,
            "forecast queries preserve the real dispatch target, reservation and passenger status");
        fixture.network.floor = 20;
        fixture.controller.cancel(rider);
        fixture.tick(2);
        helper.assertValueEqual(snapshot.arrive(0, 10, 2), first,
            "an existing immutable planning snapshot cannot change with the live elevator");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aLaterPassengerAtTheSamePickupCanJoinAnOpenBatchWithAFreePlace(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Mob first = mob(helper);
        Mob later = mob(helper);
        try {
            fixture.controller.request(first.getUUID(), 0, 10, 0);
            fixture.tick(1);
            helper.assertTrue(fixture.controller.board(helper.getLevel().getServer(), first.getUUID(), first),
                "the first passenger has walked aboard before the later call");
            fixture.controller.request(later.getUUID(), 0, 10, 2);
            fixture.tick(3);
            helper.assertTrue(fixture.controller.status(later.getUUID()) == ElevatorController.Status.BOARDING
                    && fixture.controller.place(later.getUUID()).isPresent()
                    && !fixture.controller.place(later.getUUID()).equals(fixture.controller.place(first.getUUID())),
                "the late compatible request joins the current boarding batch in a separate free place");
            fixture.network.boardingComplete = false;
            fixture.controller.board(helper.getLevel().getServer(), later.getUUID(), later);
            fixture.controller.touch(later.getUUID(), 40);
            fixture.tick(40);
            helper.assertTrue(fixture.network.targets.stream().allMatch(y -> y == 0),
                "a late joiner walking in still holds the current batch at pickup");
            fixture.network.boardingComplete = true;
            helper.assertTrue(fixture.controller.board(helper.getLevel().getServer(), later.getUUID(), later),
                "the later passenger completes its own ordinary walk in");
            fixture.tick(60);
            helper.assertTrue(fixture.network.occupants.size() == 2 && fixture.network.targets.getLast() == 10,
                "both standing passengers travel in the same physical journey");
        } finally { first.discard(); later.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void anUnregisteredBodyInTheDoorwayPreventsDepartureUntilTheDoorIsClear(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Mob rider = mob(helper);
        try {
            fixture.controller.request(rider.getUUID(), 0, 10, 0);
            fixture.tick(1);
            fixture.controller.board(helper.getLevel().getServer(), rider.getUUID(), rider);
            fixture.network.doorClear = false;
            fixture.tick(40);
            helper.assertTrue(fixture.network.occupants.containsValue(rider.getUUID())
                    && fixture.network.targets.stream().allMatch(y -> y == 0),
                "even a body outside managed reservations must clear the physical doorway before departure");
            fixture.network.doorClear = true;
            fixture.tick(60);
            helper.assertTrue(fixture.network.targets.getLast() == 10,
                "the standing passenger can depart as soon as the physical doorway is clear");
        } finally { rider.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void externallyMovingAnAlightingPassengerToAnotherFloorReleasesTheStaleJourney(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Mob rider = mob(helper);
        BlockPos landing = helper.absolutePos(new BlockPos(1, 1, 1));
        rider.setPos(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        try {
            UUID id = rider.getUUID();
            UUID next = UUID.randomUUID();
            fixture.controller.request(id, 0, 10, 0);
            fixture.tick(1);
            helper.assertTrue(fixture.controller.board(helper.getLevel().getServer(), id, rider),
                "the passenger has actually boarded before its destination arrival");
            fixture.tick(40);
            fixture.controller.request(next, 20, 0, 40);
            fixture.network.floor = 10;
            fixture.tick(41);
            fixture.network.exitComplete = false;
            fixture.controller.alight(helper.getLevel().getServer(), id, rider);
            helper.assertTrue(fixture.controller.status(id) == ElevatorController.Status.ALIGHTING,
                "the incomplete walk out still belongs to the arriving batch");
            // External staging or commands can move a body away without completing its managed walk out.
            BlockPos otherFloor = landing.above(10);
            rider.setPos(otherFloor.getX() + 0.5, otherFloor.getY(), otherFloor.getZ() + 0.5);
            fixture.network.occupants.values().removeIf(id::equals);
            var relocated = rider.position();
            int exitAttempts = fixture.network.exitAttempts;
            fixture.tick(42);
            helper.assertTrue(fixture.controller.status(id) == ElevatorController.Status.FAILED
                    && fixture.controller.place(id).isEmpty(),
                "a missing cabin occupant far from the stopped landing loses its stale journey and reservation");
            helper.assertTrue(rider.position().equals(relocated) && fixture.network.exitAttempts == exitAttempts,
                "the controller never teleports or keeps directing an externally relocated passenger");
            helper.assertTrue(fixture.network.targets.getLast() == 20
                    && fixture.controller.place(next).isPresent(),
                "the stale alighting request cannot hold the cabin or block the next waiting batch");
        } finally { rider.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void externallyMovingABoardingPassengerCannotHoldItsAboardCompanion(GameTestHelper helper) {
        abandonedWalkInReleasesTheCabin(helper, false);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aDeadBoardingPassengerCannotHoldItsAboardCompanion(GameTestHelper helper) {
        abandonedWalkInReleasesTheCabin(helper, true);
        helper.succeed();
    }

    private static void abandonedWalkInReleasesTheCabin(GameTestHelper helper, boolean died) {
        Fixture fixture = new Fixture(helper);
        Mob walking = mob(helper);
        Mob aboard = mob(helper);
        try {
            UUID abandoned = walking.getUUID();
            fixture.controller.request(abandoned, 0, 10, 0);
            fixture.controller.request(aboard.getUUID(), 0, 10, 0);
            fixture.tick(1);
            helper.assertTrue(fixture.controller.board(helper.getLevel().getServer(), aboard.getUUID(), aboard),
                "the companion is already physically standing aboard");
            fixture.network.boardingComplete = false;
            helper.assertFalse(fixture.controller.board(helper.getLevel().getServer(), abandoned, walking),
                "the other passenger has only started walking in");
            helper.assertTrue(fixture.controller.status(abandoned) == ElevatorController.Status.BOARDING,
                "the incomplete walk initially owns a live boarding reservation");
            if (died) { walking.discard(); }
            else {
                BlockPos elsewhere = helper.absolutePos(new BlockPos(12, 11, 12));
                walking.setPos(elsewhere.getX() + 0.5, elsewhere.getY(), elsewhere.getZ() + 0.5);
            }
            var abandonedPosition = walking.position();
            int exitAttempts = fixture.network.exitAttempts;
            fixture.tick(40);
            helper.assertTrue(fixture.controller.status(abandoned) == ElevatorController.Status.FAILED
                    && fixture.controller.place(abandoned).isEmpty(),
                "a dead or externally moved approaching body immediately loses its stale boarding reservation");
            helper.assertTrue(walking.position().equals(abandonedPosition)
                    && fixture.network.exitAttempts == exitAttempts,
                "the controller never moves or directs the abandoned approaching body back to the cabin");
            helper.assertTrue(fixture.network.occupants.containsValue(aboard.getUUID())
                    && fixture.network.targets.getLast() == 10,
                "the missing walk-in cannot lock the actually boarded companion at pickup");
        } finally { walking.discard(); aboard.discard(); }
    }

    private static Mob mob(GameTestHelper helper) {
        Mob body = EntityType.VILLAGER.create(helper.getLevel());
        BlockPos platform = helper.absolutePos(new BlockPos(1, 1, 1));
        body.setPos(platform.getX() + 0.5, platform.getY(), platform.getZ() + 0.5);
        body.setNoAi(true);
        helper.getLevel().addFreshEntity(body);
        return body;
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final Network network;
        final ElevatorController controller;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            network = new Network(helper);
            controller = new ElevatorController(new ElevatorRef(helper.getLevel().dimension(), BlockPos.ZERO), network);
            tick(0);
        }

        void tick(long now) { controller.tick(helper.getLevel().getServer(), now); }
    }

    private static final class Network implements ElevatorNetwork {
        final UUID vehicle = UUID.randomUUID();
        final List<Floor> floors;
        final Map<Integer, UUID> occupants = new LinkedHashMap<>();
        final Map<Integer, UUID> entering = new LinkedHashMap<>();
        final List<Integer> targets = new ArrayList<>();
        int floor;
        boolean online = true;
        boolean stopped = true;
        boolean boardingComplete = true;
        boolean exitComplete = true;
        boolean doorClear = true;
        int boardAttempts;
        int exitAttempts;

        Network(GameTestHelper helper) {
            Stances.Cells platform = new Stances.Cells(java.util.Set.of(
                WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)))));
            floors = List.of(new Floor(0, platform), new Floor(10, platform), new Floor(20, platform));
        }

        @Override
        public Optional<View> inspect(MinecraftServer server, ElevatorRef lift) {
            return online ? Optional.of(new View(vehicle, true, floor, 0.5D, stopped ? OptionalInt.of(floor) : OptionalInt.empty(),
                List.of(0, 1), List.of(0, 1).stream().filter(place -> !occupants.containsKey(place)).toList(), floors))
                : Optional.empty();
        }

        @Override
        public boolean readyToDepart(MinecraftServer server, ElevatorRef lift) { return doorClear; }

        @Override
        public boolean target(MinecraftServer server, ElevatorRef lift, int floor) {
            targets.add(floor);
            return true;
        }

        @Override
        public Optional<UUID> occupant(MinecraftServer server, ElevatorRef lift, int place) {
            return Optional.ofNullable(occupants.get(place));
        }

        @Override
        public boolean board(MinecraftServer server, ElevatorRef lift, int place, Mob body) {
            boardAttempts++;
            if (occupants.containsKey(place)) { return false; }
            entering.put(place, body.getUUID());
            if (!boardingComplete) { return false; }
            entering.remove(place, body.getUUID());
            occupants.put(place, body.getUUID());
            return true;
        }

        @Override
        public Optional<Integer> placeOf(MinecraftServer server, ElevatorRef lift, Mob body) {
            return occupants.entrySet().stream().filter(entry -> entry.getValue().equals(body.getUUID()))
                .map(Map.Entry::getKey).findFirst();
        }

        @Override
        public boolean disembark(MinecraftServer server, ElevatorRef lift, int place, Mob body) {
            exitAttempts++;
            if (!exitComplete) { return false; }
            boolean boarded = occupants.remove(place, body.getUUID());
            boolean approached = entering.remove(place, body.getUUID());
            return boarded || approached;
        }
    }
}
