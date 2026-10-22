package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TextSearchGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TextSearchGameTests.class);
    }

    @GameTest(template = "empty")
    public static void aServerWithoutTheOptionalClientModKeepsLiteralSearch(GameTestHelper helper) {
        helper.assertTrue(TextSearch.contains("铁锭 Iron Ingot", "IRON"), "literal matching ignores case");
        helper.assertTrue(TextSearch.query("铁").test("铁锭"), "Chinese text still matches literally");
        helper.assertTrue(TextSearch.query("").test("anything"), "an empty query finds everything");
        helper.assertFalse(TextSearch.contains("铁锭", "tieding"), "no implicit Pinyin or dictionary on the server");
        helper.assertTrue(TextSearch.query("I").test("iron"), "query folding is independent of the default locale");
        helper.succeed();
    }
}
