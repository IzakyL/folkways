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
import net.minecraft.world.level.block.Blocks;
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
        helper.assertTrue(!fixture.controller.seat(first).equals(fixture.controller.seat(second)),
            "each accepted passenger reserves a different seat");
        long estimate = fixture.controller.estimate(0, 20, 2);
        helper.assertTrue(estimate > fixture.controller.estimate(0, 20, 1)
                && fixture.network.targets.stream().allMatch(y -> y == 0),
            "pure monotonic prediction does not dispatch the elevator");
        fixture.network.occupants.put(fixture.controller.seat(first).orElseThrow(), UUID.randomUUID());
        fixture.tick(2);
        helper.assertTrue(fixture.controller.seat(first).isEmpty() || fixture.controller.seat(second).isEmpty(),
            "a player taking a reserved seat cannot leave both reservations active");
        fixture.controller.cancel(first);
        fixture.controller.cancel(second);
        fixture.tick(3);
        helper.assertTrue(fixture.controller.status(third) == ElevatorController.Status.BOARDING,
            "cancelling the first batch releases its seats for the next destination");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void departuresWaitForBoardingAndEveryPassengerToAlight(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Mob body = EntityType.VILLAGER.create(helper.getLevel());
        helper.getLevel().addFreshEntity(body);
        UUID rider = body.getUUID();
        fixture.controller.request(rider, 0, 10, 0);
        fixture.tick(1);
        helper.assertTrue(fixture.controller.board(helper.getLevel().getServer(), rider, body),
            "the reserved passenger boards at the pickup floor");
        fixture.tick(2);
        helper.assertTrue(fixture.network.targets.stream().allMatch(y -> y == 0),
            "boarding remains open for the dwell interval after the first passenger sits");
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
    public static void cancellationStaysAboardUntilASafeDestinationLandingExists(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Mob body = EntityType.VILLAGER.create(helper.getLevel());
        helper.getLevel().addFreshEntity(body);
        UUID rider = body.getUUID();
        fixture.controller.request(rider, 0, 10, 0);
        fixture.tick(1);
        fixture.controller.board(helper.getLevel().getServer(), rider, body);
        fixture.controller.cancel(rider);
        fixture.tick(40);
        helper.assertTrue(fixture.network.occupants.containsValue(rider),
            "cancellation during a ride still carries the passenger to the destination");
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        fixture.network.floor = 10;
        fixture.tick(41);
        helper.assertTrue(fixture.network.occupants.containsValue(rider),
            "an obstructed landing keeps the cancelled passenger safely seated");
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        fixture.tick(42);
        helper.assertTrue(!fixture.network.occupants.containsValue(rider)
                && fixture.controller.status(rider) == ElevatorController.Status.FINISHED
                && body.blockPosition().equals(helper.absolutePos(new BlockPos(1, 1, 1))),
            "clearing the landing safely disembarks and teleports the cancelled passenger");
        helper.succeed();
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
        final List<Integer> targets = new ArrayList<>();
        int floor;
        boolean online = true;

        Network(GameTestHelper helper) {
            Stances.Cells platform = new Stances.Cells(java.util.Set.of(
                WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)))));
            floors = List.of(new Floor(0, platform), new Floor(10, platform), new Floor(20, platform));
        }

        @Override
        public Optional<View> inspect(MinecraftServer server, ElevatorRef lift) {
            return online ? Optional.of(new View(vehicle, true, floor, 0.5D, OptionalInt.of(floor),
                List.of(0, 1), List.of(0, 1).stream().filter(seat -> !occupants.containsKey(seat)).toList(), floors))
                : Optional.empty();
        }

        @Override
        public boolean target(MinecraftServer server, ElevatorRef lift, int floor) {
            targets.add(floor);
            return true;
        }

        @Override
        public Optional<UUID> occupant(MinecraftServer server, ElevatorRef lift, int seat) {
            return Optional.ofNullable(occupants.get(seat));
        }

        @Override
        public boolean board(MinecraftServer server, ElevatorRef lift, int seat, Mob body) {
            return occupants.putIfAbsent(seat, body.getUUID()) == null;
        }

        @Override
        public Optional<Integer> seatOf(MinecraftServer server, ElevatorRef lift, Mob body) {
            return occupants.entrySet().stream().filter(entry -> entry.getValue().equals(body.getUUID()))
                .map(Map.Entry::getKey).findFirst();
        }

        @Override
        public void disembark(MinecraftServer server, ElevatorRef lift, int seat, Mob body) {
            occupants.remove(seat, body.getUUID());
        }
    }
}
