package io.github.izakyl.folkways.efficientconstruction;

import io.github.izakyl.folkways.plugins.build.EfficientConstruction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What this jar does on its own: flips the efficient-construction switch and hands the site its settings.
 *
 * <p>The building itself is exercised where a colony and its residents are at hand, in Folkways Vanilla's suite.
 */
@EventBusSubscriber(modid = EfficientConstructionMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class EfficientConstructionGameTests {

    private static final String TEMPLATE = "empty";

    private EfficientConstructionGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(EfficientConstructionGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void theExtensionTurnsTheEfficientSiteOn(GameTestHelper helper) {
        helper.assertTrue(EfficientConstruction.active(),
            "the extension flips the switch as soon as it loads");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void theSettingsRefuseToBuildNothing(GameTestHelper helper) {
        helper.assertTrue(refused(() -> new EfficientConstruction.Settings(0, 8, 2, true)),
            "a block cannot be laid in no time");
        helper.assertTrue(refused(() -> new EfficientConstruction.Settings(16, 0, 2, true)),
            "a site with no hands never starts");
        helper.assertTrue(refused(() -> new EfficientConstruction.Settings(16, 8, 0, true)),
            "a hand that brings no material builds nothing");
        helper.succeed();
    }

    private static boolean refused(Runnable setting) {
        try {
            setting.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
