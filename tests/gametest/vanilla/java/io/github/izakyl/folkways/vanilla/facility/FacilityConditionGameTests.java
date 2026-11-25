package io.github.izakyl.folkways.vanilla.facility;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.fixture.facility.FacilityGateFixture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
public final class FacilityConditionGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FacilityConditionGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 700)
    public static void conditionsRespectCadenceHysteresisAndCooldown(GameTestHelper helper) {
        var fixture = new FacilityGateFixture(helper);
        ResidentEntity resident = (ResidentEntity) fixture.body.mob();
        fixture.colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
        resident.joinColony(fixture.colony.id());
        fixture.colony.entered(resident);
        LaborFixture labor = new LaborFixture(fixture.colony, fixture.level);
        Cleanup.on(helper, () -> {
            labor.close();
            fixture.close();
        });
        helper.onEachTick(labor::tick);
        DefinedFacilities service = DefinedFacilities.of(fixture.colony).orElseThrow();
        var site = fixture.lever.at().cell();
        String name = "Condition gate";
        Map<String, Object> data = Map.of("name", name,
            "extent", DefinitionCodec.extent(FacilityExtent.blocks(List.of(fixture.lever.at()))),
            "controls", Map.of("lever", Map.of("part", "lever", "offset", List.of(0, 0, 0))),
            "routines", Map.of("open", Map.of("use", "folkways_core:write_all", "with", Map.of("lever", true))),
            "triggers", Map.of("auto", Map.of("kind", "condition", "routine", "open", "enabled", true,
                "when", Map.of("lever", false), "period", 20, "cooldown", 200, "hysteresis", 2)));
        var preview = service.preview(name, Optional.of(data));
        helper.assertTrue(preview.problems().isEmpty(), "valid condition: " + preview.problems());
        helper.assertTrue(service.apply(preview).isEmpty(), "condition installed");
        long start = fixture.level.getGameTime();
        long[] attempted = {0};
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(service.running(name).contains("open"), "waiting for two matching evaluations");
        }).thenExecute(() -> {
            attempted[0] = fixture.level.getGameTime();
            helper.assertTrue(attempted[0] - start >= 20, "evaluation cadence separates the two samples");
        }).thenWaitUntil(() -> {
            helper.assertTrue(fixture.open() && service.running(name).isEmpty(), "waiting for the first run");
        }).thenExecute(() -> {
            var lever = fixture.level.getBlockState(site);
            fixture.level.setBlockAndUpdate(site,
                lever.setValue(LeverBlock.POWERED, false));
        }).thenWaitUntil(() -> {
            long elapsed = fixture.level.getGameTime() - attempted[0];
            if (elapsed < 199) {
                helper.assertFalse(service.running(name).contains("open"), "cooldown must suppress a second run");
            }
            helper.assertTrue(service.running(name).contains("open"), "waiting for the next eligible evaluation");
        }).thenSucceed();
    }
}
