package io.github.izakyl.folkways.vanilla.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.RawUpgrades;
import io.github.izakyl.folkways.vanilla.supply.SupplyBox;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class NamespaceUpgradeGameTests {
    private NamespaceUpgradeGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(NamespaceUpgradeGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void supplyBoxWhitelistQueriesUpgradeBeforeBusinessDecode(GameTestHelper helper) throws Exception {
        var registries = helper.getLevel().registryAccess();
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("folkways:creative_supply_box"));
        CompoundTag raw = TagParser.parseTag("""
            {id:"folkways:creative_supply_box",all:false,revision:7L,
              whitelist:["ifolkways:colony_book","tfolkways:resident_food",
                "qfolkways:tag_filter | #folkways:resident_food"],generated:{"folkways:colony_book":5L},
              NeoForgeData:{"folkways:data_version":2}}
            """);
        var box = (SupplyBox) BlockEntity.loadStatic(BlockPos.ZERO, block.defaultBlockState(), raw, registries);
        helper.assertTrue(box != null, "legacy type alias loads the SupplyBox");
        helper.assertValueEqual(box.filters().stream().map(rule -> rule.write()).toList(), List.of(
            "ifolkways_core:colony_book", "tfolkways_vanilla:resident_food",
            "qfolkways_core:tag_filter | #folkways_vanilla:resident_food"), "item, tag and query filters migrate");
        helper.assertTrue(box.revision() == 7 && !box.allItems(), "configuration preserved");
        helper.assertTrue(box.generated().get("folkways_core:colony_book") == 5, "generated counts preserved");
        CompoundTag saved = box.saveWithFullMetadata(registries);
        helper.assertFalse(saved.toString().contains("folkways:"), "only current ids are saved");
        helper.assertTrue(saved.getCompound("NeoForgeData").getInt(RawUpgrades.VERSION) == 3,
            "block entity advances through its existing migration chain");
        helper.succeed();
    }
}
