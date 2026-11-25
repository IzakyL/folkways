package io.github.izakyl.folkways.airline;

import io.github.izakyl.folkways.core.combat.api.AirportTraffic;
import io.github.izakyl.folkways.core.combat.api.AirportTraffic.Priority;
import io.github.izakyl.folkways.core.combat.api.AirportTraffic.State;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.UUID;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysAirlineMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_airline")
@PrefixGameTestTemplate(false)
public final class AirTrafficGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(AirTrafficGameTests.class);
    }

    private static List<AirportTraffic.Resource> resources(GameTestHelper helper) {
        var first = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
        var last = new WorldPos(first.realm(), first.cell().east(8));
        return List.of(new AirportTraffic.Resource(AirportTraffic.Kind.RUNWAY, first, last));
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void fifoWithinEachPriority(GameTestHelper helper) {
        for (Priority priority : Priority.values()) {
            var resources = resources(helper);
            try (var first = AirportTraffic.request(helper.getLevel(), priority, resources).orElseThrow();
                 var second = AirportTraffic.request(helper.getLevel(), priority, resources).orElseThrow();
                 var third = AirportTraffic.request(helper.getLevel(), priority, resources).orElseThrow()) {
                helper.assertValueEqual(first.state(), State.HELD, "first holds");
                helper.assertValueEqual(second.state(), State.WAITING, "second waits");
                helper.assertValueEqual(third.state(), State.WAITING, "third waits");
                first.close();
                helper.assertValueEqual(second.state(), State.HELD, "second goes next");
                helper.assertValueEqual(third.state(), State.WAITING, "third cannot pass second");
                second.close();
                helper.assertValueEqual(third.state(), State.HELD, "third goes last");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void releaseFreesResource(GameTestHelper helper) {
        var resources = resources(helper);
        var first = AirportTraffic.request(helper.getLevel(), Priority.COMBAT, resources).orElseThrow();
        try (var waiting = AirportTraffic.request(helper.getLevel(), Priority.COMBAT, resources).orElseThrow()) {
            first.close();
            first.close();
            helper.assertValueEqual(first.state(), State.RELEASED, "release is idempotent");
            helper.assertValueEqual(waiting.state(), State.HELD, "release immediately grants waiter");
        } finally {
            first.close();
        }
        helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void assignQueuesLandingBeforeLaterDepartures(GameTestHelper helper) {
        var at = resources(helper).getFirst().first().above();
        var site = new LandingSites.Site("fifo", "FIFO", LandingSites.Kind.RUNWAY_END, at, 0, 24, 5);
        try (var first = AirportTraffic.assign(helper.getLevel(), site,
                new AirportTraffic.Aircraft(UUID.randomUUID(), 10, 3, true)).orElseThrow();
             var landing = AirportTraffic.assign(helper.getLevel(), site,
                new AirportTraffic.Aircraft(UUID.randomUUID(), 10, 3, false)).orElseThrow();
             var departure = AirportTraffic.assign(helper.getLevel(), site,
                new AirportTraffic.Aircraft(UUID.randomUUID(), 10, 3, true)).orElseThrow()) {
            helper.assertValueEqual(landing.state(), State.WAITING, "busy landing remains queued");
            first.close();
            helper.assertValueEqual(landing.state(), State.HELD, "landing receives released runway first");
            helper.assertValueEqual(departure.state(), State.WAITING, "later departure cannot starve landing");
            landing.close();
            helper.assertValueEqual(departure.state(), State.HELD, "departure follows landing");
        }
        helper.succeed();
    }

}
