package io.github.izakyl.folkways.vanilla.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.DataVersion;
import io.github.izakyl.folkways.core.base.persist.upgrade.RawUpgrades;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysItems;
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
    public static void publishedNetworkIdsStayInCore(GameTestHelper helper) throws Exception {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse("folkways_core:network_pattern"));
        helper.assertTrue(item == FolkwaysItems.NETWORK_PATTERN.get(), "published ID resolves to the Core item");
        helper.assertValueEqual(BuiltInRegistries.ITEM.getKey(item).toString(), "folkways_core:network_pattern",
            "Core owns the registered item");

        var raw = TagParser.parseTag("""
            {keeps:{"folkways_core:ae2_network_buffers":{jobs:1}},
             filters:["ifolkways_core:network_pattern","ifolkways_carving:pavilion"],
             settings:{items:[{query:"folkways_core:network_pattern | folkways_core:colony_book"}]},
             withheld:["folkways_core:network_service"]}
            """);
        Upgrades.upgrade(Surface.COLONY_REGISTRY, raw, 4);
        helper.assertTrue(raw.getCompound("keeps").contains("folkways_core:ae2_network_buffers"),
            "provider state keeps its published key");
        var filters = raw.getList("filters", 8);
        helper.assertValueEqual(filters.getString(0), "ifolkways_core:network_pattern", "item filter keeps its published ID");
        helper.assertValueEqual(filters.getString(1), "ifolkways_vanilla:pavilion", "pattern migrates");
        helper.assertValueEqual(raw.getCompound("settings").getList("items", 10).getCompound(0).getString("query"),
            "folkways_core:network_pattern | folkways_core:colony_book", "Core IDs are preserved in queries");
        helper.assertValueEqual(raw.getList("withheld", 8).getString(0), "folkways_core:network_service",
            "vocation keeps its published ID");
        helper.succeed();
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
        helper.assertTrue(saved.getCompound("NeoForgeData").getInt(RawUpgrades.VERSION) == DataVersion.CURRENT,
            "block entity advances through its existing migration chain");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void v2PatrolLoadoutsUpgradeThroughNamespacesAndEquipment(GameTestHelper helper) throws Exception {
        CompoundTag raw = TagParser.parseTag("""
            {colonies:[{keeps:{"folkways:patrol_loadouts":{profiles:[],assignments:[]},
              "folkways:patrol_staffing":{targets:[]}}}]}
            """);
        Upgrades.upgrade(Surface.COLONY_REGISTRY, raw, 2);
        var keeps = raw.getList("colonies", 10).getCompound(0).getCompound("keeps");
        helper.assertFalse(keeps.contains("folkways:patrol_loadouts")
            || keeps.contains("folkways_vanilla:patrol_loadouts"), "both old loadout keys are replaced");
        helper.assertValueEqual(keeps.getCompound("folkways_core:equipment"),
            TagParser.parseTag("{profiles:[],assignments:[]}"), "namespace and equipment migrations compose");
        helper.assertTrue(keeps.contains("folkways_vanilla:patrol_staffing"), "staffing keeps its namespaced bag");
        helper.succeed();
    }
}
