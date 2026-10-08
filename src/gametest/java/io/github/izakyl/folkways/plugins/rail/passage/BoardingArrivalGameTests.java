package io.github.izakyl.folkways.plugins.rail.passage;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.walk.OnFoot;
import io.github.izakyl.folkways.plugins.rail.domain.Boarding;
import io.github.izakyl.folkways.plugins.rail.domain.RailRefusal;
import io.github.izakyl.folkways.plugins.rail.domain.SeatRef;
import io.github.izakyl.folkways.plugins.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.plugins.rail.domain.TransitNetworks;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BoardingArrivalGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BoardingArrivalGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void walkingArrivalAtTheEdgeOfAPlatformAllowsBoarding(GameTestHelper helper) {
        var level = helper.getLevel();
        var cell = helper.absolutePos(new BlockPos(1, 1, 1));
        var platform = new Stances.Cells(Set.of(WorldPos.of(level, cell)));
        var leg = new RailLegs.Leg(UUID.randomUUID(), "Alpha", "Beta", platform, platform,
            new Timetable(List.of("Alpha", "Beta"), new long[] {0, 100}, new long[] {20, 100}));
        var body = PersonBody.RESIDENT.get().create(level);
        body.setPos(cell.getX() - 0.2, cell.getY(), cell.getZ() + 0.5);
        helper.assertTrue(!body.blockPosition().equals(cell), "the feet are in the adjacent block");
        helper.assertTrue(new OnFoot(1, LaborRefusal.NO_WAY_THERE).step(level, body, platform.cells())
            instanceof Going.Arrived, "walking already considers this position arrived");
        var seat = new SeatRef(UUID.randomUUID(), 0);
        AtomicInteger boarded = new AtomicInteger();
        TransitNetwork before = TransitNetworks.get();
        TransitNetwork network = (TransitNetwork) Proxy.newProxyInstance(TransitNetwork.class.getClassLoader(),
            new Class<?>[] {TransitNetwork.class}, (proxy, method, args) -> switch (method.getName()) {
                case "isAvailable", "isServiceable", "hasConductor" -> true;
                case "fault" -> Optional.empty();
                case "scheduledStations" -> List.of("Alpha", "Beta");
                case "currentStation" -> Optional.of("Alpha");
                case "passengerSeats" -> List.of(seat);
                case "occupant" -> Optional.empty();
                case "board" -> { boarded.incrementAndGet(); yield true; }
                default -> throw new AssertionError("unexpected transit call: " + method.getName());
            });
        try {
            TransitNetworks.install(network);
            var ride = new RailConveyance(leg);
            for (int tick = 0; tick < Boarding.seatTicks(); tick++) {
                ride.step(level, body, platform.cells());
            }
            helper.assertTrue(boarded.get() == 1, "boarding must accept the same arrival tolerance as walking");
            body.setPos(cell.getX() - 5, cell.getY(), cell.getZ() + 0.5);
            var far = new RailConveyance(leg);
            for (int tick = 0; tick <= Boarding.seatTicks(); tick++) {
                far.step(level, body, platform.cells());
            }
            helper.assertTrue(boarded.get() == 1, "residents far from the platform cannot board");
        } finally {
            TransitNetworks.install(before);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aRiderGivesUpOnceTheRunTheyCaughtHasLeft(GameTestHelper helper) {
        var level = helper.getLevel();
        var cell = helper.absolutePos(new BlockPos(1, 1, 1));
        var platform = new Stances.Cells(Set.of(WorldPos.of(level, cell)));
        var runs = new Timetable(List.of("Alpha", "Beta", "Alpha", "Beta"),
            new long[] {0, 100, 200, 300}, new long[] {20, 120, 220, 320});
        var leg = new RailLegs.Leg(UUID.randomUUID(), "Alpha", "Beta", platform, platform, runs, OptionalLong.of(1));
        var body = PersonBody.RESIDENT.get().create(level);
        body.setPos(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
        AtomicLong departures = new AtomicLong();
        TransitNetwork before = TransitNetworks.get();
        TransitNetwork network = (TransitNetwork) Proxy.newProxyInstance(TransitNetwork.class.getClassLoader(),
            new Class<?>[] {TransitNetwork.class}, (proxy, method, args) -> switch (method.getName()) {
                case "isAvailable", "isServiceable", "hasConductor" -> true;
                case "fault", "currentStation" -> Optional.empty();
                case "scheduledStations" -> List.of("Alpha", "Beta");
                case "departures" -> departures.get();
                default -> throw new AssertionError("unexpected transit call: " + method.getName());
            });
        try {
            TransitNetworks.install(network);
            var ride = new RailConveyance(leg);
            helper.assertTrue(ride.step(level, body, platform.cells()) == Going.UNDERWAY,
                "the caught run has not come yet, so the rider waits for it");
            departures.set(1);
            helper.assertTrue(ride.step(level, body, platform.cells())
                    .equals(new Going.Failed(RailRefusal.RUN_MISSED)),
                "the caught run has left, so the rider gives up though a later run would still take them");
        } finally {
            TransitNetworks.install(before);
        }
        helper.succeed();
    }
}
