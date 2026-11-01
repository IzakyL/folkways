package io.github.izakyl.folkways.efficientconstruction;

import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.plugins.build.BuildSite;
import io.github.izakyl.folkways.plugins.build.BuildTarget;
import io.github.izakyl.folkways.plugins.build.EfficientConstruction;
import io.github.izakyl.folkways.plugins.build.EfficientSite;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
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

    /**
     * The seam the migration introduced: a site raised by the add-on is asked for through the switch, and the add-on
     * is what answers. Without it the site would keep the ordinary crew, which the vanilla suite covers on its own
     * rows where this jar is not installed at all.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void theAddonRaisesTheSiteItIsAskedFor(GameTestHelper helper) {
        BuildTarget target = new BuildTarget(Realm.of(helper.getLevel()), Map.of());
        EfficientSite site = EfficientConstruction.open(UUID.randomUUID(), new NeverAsked(), target,
            new HashSet<>(), new HashSet<>(), new HashMap<>());
        helper.assertTrue(site != null, "the add-on hands back the site that raises itself");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void aTripIsWholeStacksOfMaterial(GameTestHelper helper) {
        EfficientConstruction.Settings settings = EfficientConstruction.settings();
        helper.assertTrue(settings.stacksPerTrip() >= 1, "a trip asks for at least one stack");
        helper.assertTrue(settings.itemsPerTrip() == settings.stacksPerTrip() * 64L,
            "a trip is counted in whole stacks");
        helper.succeed();
    }

    /** A colony that is never asked anything: these tests make the site, they do not run it. */
    private static final class NeverAsked implements BuildSite.Asks {

        @Override
        public void submit(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                Grown work, BiConsumer<UUID, Ending> ended) {
        }

        @Override
        public void withdraw(UUID node) {
        }
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
