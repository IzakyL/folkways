package io.github.izakyl.folkways.vanilla.cookery;

import io.github.izakyl.folkways.core.front.engine.net.FacilityCardReads;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class CookeryBoardPurityGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CookeryBoardPurityGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void nativeStationsSeedsAndAllBoardsArePureColdAndWarm(GameTestHelper helper) {
        FacilityCardReads.assertPure(helper, List.of("chopping_board", "stockpot", "pot", "bamboo_tray", "teapot", "stove",
            "shawarma_spit", "steamer", "millstone").stream()
            .map(path -> ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", path)).toList());
        helper.succeed();
    }
}
