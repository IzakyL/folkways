package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ObservationBudgetGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ObservationBudgetGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void noFirstStepWhenEmpty(GameTestHelper helper) {
        ObservationBudget budget = new ObservationBudget(5, 0, () -> 100L);
        helper.assertTrue(budget.spent(), "zero budget must stop the first step");
        helper.succeed();
    }
}
