package io.github.izakyl.folkways.vanilla.cookery.stove;

import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class StoveReadGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StoveReadGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unloadedStoveStatusDoesNotLoadChunks(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = WorldPos.of(level, new BlockPos(28000000, 64, 28000000));
        Purity.assertPure(helper, "unloaded stove", () -> new StoveStation().status(level, at));
        helper.succeed();
    }
}
