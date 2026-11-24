package io.github.izakyl.folkways.vanilla.facility;

import io.github.izakyl.folkways.core.facility.api.Prefabs;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.travel.api.Passages;
import io.github.izakyl.folkways.core.travel.api.TrafficFeedback;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.Trip;
import io.github.izakyl.folkways.core.travel.internal.Urgency;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.vanilla.fixture.facility.FacilityGateFixture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.LeverBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class GateGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(GateGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void residentWalksThroughAnOpenGate(GameTestHelper helper) {
        FacilityGateFixture fixture = new FacilityGateFixture(helper);
        Cleanup.on(helper, fixture::close);
        helper.assertTrue(Prefabs.at(fixture.level, fixture.door.cell()).isEmpty()
            && Passages.published(fixture.level, fixture.colony.id()).isEmpty(),
            "the door and lever have no gate projections before designation");
        fixture.install();
        fixture.powerLever();
        helper.runAfterDelay(2, () -> crossOpenGate(helper, fixture));
    }

    private static void crossOpenGate(GameTestHelper helper, FacilityGateFixture fixture) {
        helper.assertTrue(fixture.open(), "the powered lever opened the door");
        Faring answer = fixture.route();
        helper.assertTrue(answer instanceof Faring.Yes yes
            && yes.by().legs().stream().anyMatch(leg -> leg.hop().isPresent()), "route uses the gate hop");
        Trip trip = Trip.to(fixture::ways, fixture.body.resident(), fixture.body.locomotion(), fixture.body.mob(),
            fixture.goals, 1, LaborRefusal.NO_WAY_THERE, Urgency.WALKING, TrafficFeedback.NONE).orElseThrow();
        trip.setOut(fixture.level, fixture.body.mob(), fixture.goals);
        Cleanup.on(helper, () -> trip.release(fixture.level, fixture.body.mob()));
        helper.startSequence().thenWaitUntil(() -> {
            Going result = trip.step(fixture.level, fixture.body.mob(), fixture.goals);
            helper.assertFalse(result instanceof Going.Failed, "trip failed at " + trip.describe()
                + " from " + fixture.body.mob().position());
            helper.assertTrue(result instanceof Going.Arrived, "resident is still walking");
        }).thenExecute(() -> {
            helper.assertTrue(fixture.body.mob().getX() > fixture.door.cell().getX() + 2,
                "the resident reached the far side");
            helper.assertTrue(fixture.open(), "the iron door remains open");
            helper.assertTrue(fixture.level.getBlockState(fixture.lever.at().cell())
                .getValue(LeverBlock.POWERED), "the lever remains powered");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aLockedGateDoesNotOfferARoute(GameTestHelper helper) {
        try (FacilityGateFixture fixture = new FacilityGateFixture(helper)) {
            fixture.install();
            helper.assertTrue(fixture.route() instanceof Faring.No, "a closed gate with no detour gives no route");
            helper.assertFalse(fixture.open(), "route finding did not power the gate");
            helper.assertTrue(Passages.published(fixture.level, fixture.colony.id()).isEmpty(),
                "a closed gate publishes no hops");
        }
        helper.succeed();
    }
}
