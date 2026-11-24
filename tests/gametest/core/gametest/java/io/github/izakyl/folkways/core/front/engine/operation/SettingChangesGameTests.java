package io.github.izakyl.folkways.core.front.engine.operation;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class SettingChangesGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SettingChangesGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void sharedValidationKeepsBookClampingAndItemRules(GameTestHelper helper) {
        var icon = ResourceLocation.withDefaultNamespace("stone");
        var count = new Schema.Setting.Count("n", "test", icon, 1, 10, 1);
        helper.assertTrue(SettingChanges.validated(count, new ColonySettings.Value.Count(100)).orElseThrow()
                .equals(new ColonySettings.Value.Count(10)), "counts clamp like the GUI");
        helper.assertTrue(SettingChanges.validated(count, new ColonySettings.Value.Flag(true)).isEmpty(),
                "wrong value kind refused");
        var items = new Schema.Setting.Items("items", "test", icon, List.of(), true, true);
        var stone = ItemFilter.item(icon);
        var stick = ItemFilter.item(ResourceLocation.withDefaultNamespace("stick"));
        var filters = List.of(stick, stone, stone);
        helper.assertTrue(SettingChanges.keptItems(items, filters).equals(List.of(stone)),
                "single block filters reject nonblocks and deduplicate");
        helper.assertTrue(SettingChanges.validated(items, new ColonySettings.Value.Items(List.of(stick))).isEmpty(),
                "a single item setting needs a valid item");
        helper.succeed();
    }
}
