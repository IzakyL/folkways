package io.github.izakyl.folkways.core.world.api;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemQuery;
import io.github.izakyl.folkways.core.testing.Purity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MemoizationGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(MemoizationGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void itemMemoizationIsPureAndTagReloadInvalidatesResults(GameTestHelper helper) {
        var spec = Goods.specOf(Items.BREAD.getDefaultInstance());
        var query = ItemQuery.parse("minecraft:bread").orElseThrow();
        Goods.forgetTags();
        ItemQuery.forget();
        Purity.assertPure(helper, "cold item memoization", () -> {
            Goods.members(spec);
            Goods.stackSize(spec);
            query.items();
        });
        var members = Goods.members(spec);
        var matches = query.items();
        helper.assertTrue(Goods.members(spec) == members, "members reuse the registry-derived set");
        helper.assertTrue(query.items() == matches, "queries reuse the registry-derived matches");
        int size = Goods.stackSize(spec);
        Goods.forgetTags();
        ItemQuery.forget();
        helper.assertTrue(Goods.members(spec) != members, "tag reload discards old member sets");
        helper.assertTrue(query.items() != matches, "tag reload discards old query matches");
        helper.assertValueEqual(Goods.members(spec), members, "unchanged tags preserve membership");
        helper.assertValueEqual(query.items(), matches, "unchanged tags preserve query matches");
        helper.assertValueEqual(Goods.stackSize(spec), size, "unchanged tags preserve stack size");
        helper.succeed();
    }
}
