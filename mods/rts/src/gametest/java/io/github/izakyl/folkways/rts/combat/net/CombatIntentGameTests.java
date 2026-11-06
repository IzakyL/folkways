package io.github.izakyl.folkways.rts.combat.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.rts.FolkwaysRtsMod;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// Packet round trips are exercised end to end by the combat-rts-input client suite.
@EventBusSubscriber(modid = FolkwaysRtsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CombatIntentGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CombatIntentGameTests.class);
    }

    @GameTest(template = "empty")
    public static void routeCycleStartAcceptsOnlyAPrefixAndAClosedLoop(GameTestHelper helper) {
        helper.assertTrue(SelectionOrders.validCycle(3, 1), "a prefix followed by a two-point loop is accepted");
        helper.assertFalse(SelectionOrders.validCycle(3, 2), "a one-point loop is rejected");
        helper.assertFalse(SelectionOrders.validCycle(3, -1), "an unclosed multi-point route is rejected");
        helper.assertFalse(SelectionOrders.validCycle(1, -2), "unknown negative modes are rejected");
        helper.assertTrue(SelectionOrders.validCycle(1, -1), "a single move stays compatible");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void suspendIntentCannotCarryClientAuthoredResidents(GameTestHelper helper) {
        try {
            new CombatControlPacket(8, UUID.randomUUID(), CombatControlPacket.Operation.SUSPEND, 0,
                List.of(UUID.randomUUID()));
            helper.fail("suspension must not acquire identifiers supplied by the client");
        } catch (IllegalArgumentException expected) {
        }
        helper.succeed();
    }
}
