package io.github.izakyl.folkways.core.travel.internal;

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
public final class TickBudgetGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TickBudgetGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void ledgerSharesSlackAndChargesSites(GameTestHelper helper) {
        try {
            TickBudget.beginTick(100, 100, 1_000);
            helper.assertTrue(TickBudget.remaining() == 0, "zero slack");
            TickBudget.beginTick(99, 100, 1_000);
            helper.assertTrue(TickBudget.remaining() == 0, "negative slack");
            TickBudget.beginTick(500, 100, 1_000);
            helper.assertTrue(TickBudget.remaining() == 200, "half slack");
            TickBudget.beginTick(500, 100, 120);
            helper.assertTrue(TickBudget.remaining() == 120, "ceiling");
            TickBudget.openSite(3);
            helper.assertTrue(TickBudget.remaining() == 40, "first share");
            TickBudget.spent(20);
            TickBudget.closeSite();
            TickBudget.openSite(2);
            helper.assertTrue(TickBudget.remaining() == 50, "unused share flows forward");
            TickBudget.spent(50);
            TickBudget.closeSite();
            TickBudget.openSite(1);
            helper.assertTrue(TickBudget.remaining() == 50, "last share");
            TickBudget.spent(80);
            TickBudget.closeSite();
            helper.assertTrue(TickBudget.remaining() == 0, "overshoot exhausts ledger");
        } finally {
            TickBudget.beginTick(helper.getLevel().getServer(), 0);
        }
        helper.succeed();
    }
}
