package io.github.izakyl.folkways.core.base.persist.upgrade;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class NamespaceUpgradeGameTests {
    private NamespaceUpgradeGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(NamespaceUpgradeGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void ownedSurfacesRewriteExactIdsAndOnlyTheirOwnTextPayloads(GameTestHelper helper) throws Exception {
        for (Surface surface : Surface.values()) {
            CompoundTag raw = TagParser.parseTag("""
                {id:"folkways:resident","folkways:body":true,NeoForgeData:{"folkways:data_version":2},
                 "neoforge:attachments":{"folkways:resident_capabilities":{withheld:["folkways:hauling"],
                   keenness:{"folkways:farming":4}},"folkways:body_state":{Perks:{
                   vocations:{"folkways:crafting":{xp:123,level:0,labor_remainder:0,owned:[]}},
                   global:{xp:0,level:0,labor_remainder:0,owned:[]}}}},
                 Inventory:[{id:"folkways:tag_filter",count:3,components:{
                   "folkways:filter_tag":"folkways:resident_food","!folkways:book_gesture":{}}}],
                 palette:[{Name:"folkways:creative_supply_box",Properties:{}}],
                 filters:["tfolkways:resident_food","ifolkways:colony_book"],
                 settings:{items:[{query:"#folkways:resident_food | folkways:colony_book"}]},
                 whitelist:["qfolkways:colony_book | #folkways:resident_food"],
                 unknown:"folkways:unknown_namespace_upgrade_test",external:"minecraft:stone",number:17}
                """);
            Map<String, String> extensions = Map.of("folkways_carts", "cart_line", "folkways_create", "firefighting",
                "folkways_cookery", "preparing_food", "folkways_carving", "chiselsandbits",
                "folkways_golems", "golem_body", "folkways_firearms", "tacz");
            extensions.forEach((module, path) -> raw.putString("extension/" + module, "folkways:" + path));
            Upgrades.upgrade(surface, raw, 2);
            extensions.forEach((module, path) -> helper.assertValueEqual(raw.getString("extension/" + module),
                (ModList.get().isLoaded(module) ? module : "folkways") + ":" + path, surface + " " + module));
            helper.assertValueEqual(raw.getString("id"), "folkways_vanilla:resident", surface + " entity type");
            helper.assertTrue(raw.getBoolean("folkways_core:body"), surface + " body key");
            var attachments = raw.getCompound("neoforge:attachments");
            var licences = attachments.getCompound("folkways_core:resident_capabilities");
            helper.assertValueEqual(licences.getList("withheld", 8).getString(0), "folkways_core:hauling",
                surface + " withheld vocation");
            helper.assertTrue(licences.getCompound("keenness").getInt("folkways_vanilla:farming") == 4,
                surface + " keenness key and value");
            helper.assertTrue(attachments.getCompound("folkways_core:body_state")
                .getCompound("Perks").getCompound("vocations")
                .getCompound("folkways_vanilla:crafting").getInt("xp") == 123, surface + " perk track");
            var stack = raw.getList("Inventory", 10).getCompound(0);
            helper.assertValueEqual(stack.getString("id"), "folkways_core:tag_filter", surface + " item id");
            helper.assertTrue(stack.getInt("count") == 3, surface + " stack count");
            helper.assertValueEqual(stack.getCompound("components").getString("folkways_core:filter_tag"),
                "folkways_vanilla:resident_food", surface + " component id and contents");
            helper.assertTrue(stack.getCompound("components").contains("!folkways_core:book_gesture"),
                surface + " removed component key");
            helper.assertValueEqual(raw.getList("palette", 10).getCompound(0).getString("Name"),
                "folkways_vanilla:creative_supply_box", surface + " palette entry");
            helper.assertValueEqual(raw.getList("filters", 8).getString(0), "tfolkways_vanilla:resident_food",
                surface + " encoded tag filter");
            helper.assertValueEqual(raw.getList("filters", 8).getString(1), "ifolkways_core:colony_book",
                surface + " encoded item filter");
            helper.assertValueEqual(raw.getCompound("settings").getList("items", 10).getCompound(0)
                .getString("query"), surface == Surface.COLONY_REGISTRY
                    ? "#folkways_vanilla:resident_food | folkways_core:colony_book"
                    : "#folkways:resident_food | folkways:colony_book",
                surface + " query text is owned by Core registry migration");
            helper.assertValueEqual(raw.getList("whitelist", 8).getString(0),
                surface == Surface.BLOCK_ENTITY
                    ? "qfolkways_core:colony_book | #folkways_vanilla:resident_food"
                    : "qfolkways:colony_book | #folkways:resident_food", surface + " SupplyBox query ownership");
            helper.assertValueEqual(raw.getString("unknown"), "folkways:unknown_namespace_upgrade_test",
                surface + " unknown id preserved");
            helper.assertTrue(raw.getInt("number") == 17 && raw.getString("external").equals("minecraft:stone"),
                surface + " unrelated values preserved");
            CompoundTag once = raw.copy();
            Upgrades.upgrade(surface, raw, 2);
            helper.assertValueEqual(raw, once, surface + " idempotent");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void registryV1ClaimsThenV3OwnersAndWorkDefaults(GameTestHelper helper) throws Exception {
        for (int version : List.of(1, 2)) {
            CompoundTag raw = TagParser.parseTag("""
                {colonies:[{holdings:{"folkways:farm":{owner:"folkways:person"}},keeps:{
                  "folkways:transport":{id:"folkways:haul"},"folkways:supply":{stock:9},
                  "folkways:work_defaults":{kinds:{"folkways:resident":{trades:{"folkways:farming":
                    {allowed:true,priority:4}}}}},"folkways:carts":{load:5},"folkways:firefighting":{water:7},
                  "folkways:cookery":{batch:11}}}]}
                """);
            CompoundTag colony = raw.getList("colonies", 10).getCompound(0);
            if (version == 2) {
                colony.put("claims", colony.getCompound("holdings"));
                colony.remove("holdings");
            }
            Upgrades.upgrade(Surface.COLONY_REGISTRY, raw, version);
            helper.assertTrue(!colony.contains("holdings") && colony.getCompound("claims")
                .getCompound("folkways_vanilla:farm").getString("owner").equals("folkways_vanilla:person"),
                "v" + version + " claims shape and ids compose");
            CompoundTag keeps = colony.getCompound("keeps");
            helper.assertTrue(keeps.getCompound("folkways_vanilla:supply").getInt("stock") == 9,
                "supply plugin owns its persisted bag");
            helper.assertValueEqual(keeps.getCompound("folkways_core:transport").getString("id"),
                "folkways_core:haul", "transport engine owns its bag");
            helper.assertTrue(keeps.getCompound("folkways_core:work_defaults").getCompound("kinds")
                .getCompound("folkways_vanilla:resident").getCompound("trades")
                .getCompound("folkways_vanilla:farming").getInt("priority") == 4, "work defaults preserve priorities");
            for (String module : List.of("carts", "create", "cookery")) {
                String bag = switch (module) {
                    case "create" -> "firefighting";
                    default -> module;
                };
                String namespace = ModList.get().isLoaded("folkways_" + module) ? "folkways_" + module : "folkways";
                helper.assertTrue(keeps.contains(namespace + ":" + bag), "installed owner " + module);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void colonySettingsQueriesNormalizeAcrossModuleTables(GameTestHelper helper) throws Exception {
        CompoundTag raw = TagParser.parseTag("""
            {colonies:[{keeps:{"folkways:front":{drawn:{holding:{food:{items:[
              {query:"#folkways:resident_food | folkways:colony_book"}]} }},settings:{"folkways:farming":{
              food:{items:[{query:"folkways:tag_filter | folkways:unknown_query_test | folkways:resident_*"}]}}}}}}],
              query:"folkways:colony_book | folkways:tag_filter"}
            """);
        Upgrades.upgrade(Surface.COLONY_REGISTRY, raw, 2);
        var front = raw.getList("colonies", 10).getCompound(0).getCompound("keeps")
            .getCompound("folkways_core:front");
        var settings = io.github.izakyl.folkways.core.front.engine.colony.ColonySettings.load(
            io.github.izakyl.folkways.core.base.persist.Reader.of(front.getCompound("drawn").getCompound("holding")));
        helper.assertValueEqual(settings.entries("food").getFirst().text(),
            "#folkways_vanilla:resident_food | folkways_core:colony_book", "drawn settings query normalizes");
        helper.assertValueEqual(front.getCompound("settings").getCompound("folkways_vanilla:farming")
            .getCompound("food").getList("items", 10).getCompound(0).getString("query"),
            "folkways_core:tag_filter | folkways:unknown_query_test | folkways:resident_*",
            "scoped settings use all installed mappings and preserve unknown/wildcard ids");
        helper.assertValueEqual(raw.getString("query"), "folkways:colony_book | folkways:tag_filter",
            "unrelated query text is not a ColonySettings payload");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void registryAliasesAndItemCodecPreserveV2Content(GameTestHelper helper) throws Exception {
        var registries = helper.getLevel().registryAccess();
        var book = BuiltInRegistries.ITEM.get(ResourceLocation.parse("folkways:colony_book"));
        helper.assertValueEqual(BuiltInRegistries.ITEM.getKey(book).toString(), "folkways_core:colony_book",
            "item alias resolves without decoding the chunk");
        var type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("folkways:resident"));
        helper.assertValueEqual(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), "folkways_vanilla:resident",
            "entity type alias resolves before Entity.load");
        helper.assertTrue(type != EntityType.PIG, "resident type remains registered");
        var resident = type.create(helper.getLevel());
        CompoundTag entity = resident.saveWithoutId(new CompoundTag());
        entity.putString("id", "folkways:resident");
        CompoundTag persistent = entity.getCompound("NeoForgeData");
        persistent.remove(RawUpgrades.VERSION);
        persistent.putInt("folkways:data_version", 2);
        entity.putBoolean("folkways:body", entity.getBoolean("folkways_core:body"));
        entity.remove("folkways_core:body");
        CompoundTag stores = entity.getCompound("neoforge:attachments");
        CompoundTag licences = stores.getCompound("folkways_core:resident_capabilities");
        licences.getCompound("keenness").putInt("folkways:farming", 4);
        stores.put("folkways:resident_capabilities", licences);
        stores.remove("folkways_core:resident_capabilities");
        CompoundTag body = stores.getCompound("folkways_core:body_state");
        body.getCompound("Perks").getCompound("vocations").put("folkways:crafting", TagParser.parseTag(
            "{xp:123,level:0,labor_remainder:0,owned:[]}"));
        stores.put("folkways:body_state", body);
        stores.remove("folkways_core:body_state");
        var loaded = EntityType.create(entity, helper.getLevel()).orElseThrow();
        CompoundTag savedEntity = loaded.saveWithoutId(new CompoundTag());
        var savedStores = savedEntity.getCompound("neoforge:attachments");
        helper.assertTrue(savedStores.getCompound("folkways_core:resident_capabilities").getCompound("keenness")
            .getInt("folkways_vanilla:farming") == 4, "v2 licences decode after migration");
        helper.assertTrue(savedStores.getCompound("folkways_core:body_state").getCompound("Perks")
            .getCompound("vocations").getCompound("folkways_vanilla:crafting").getInt("xp") == 123,
            "v2 perk vocation progress survives real entity decoding");
        helper.assertTrue(savedEntity.getCompound("NeoForgeData").getInt(RawUpgrades.VERSION) == 3,
            "the loaded body writes the v3 stamp");
        resident.discard();
        loaded.discard();
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("folkways:creative_supply_box"));
        helper.assertValueEqual(BuiltInRegistries.BLOCK.getKey(block).toString(),
            "folkways_vanilla:creative_supply_box", "palette block alias");
        CompoundTag be = TagParser.parseTag("""
            {id:"folkways:creative_supply_box",all:true,revision:0L,generated:{},
             NeoForgeData:{"folkways:data_version":2}}
            """);
        BlockEntity restored = BlockEntity.loadStatic(BlockPos.ZERO, block.defaultBlockState(), be, registries);
        helper.assertTrue(restored != null, "old placed block entity survives static lookup and load");
        CompoundTag stack = TagParser.parseTag("""
            {id:"folkways:tag_filter",count:2,components:{"folkways:filter_tag":"folkways:resident_food"}}
            """);
        ItemStack parsed = ItemStack.parse(registries, stack).orElseThrow();
        helper.assertTrue(parsed.getCount() == 2, "old component-bearing stack decodes");
        CompoundTag saved = (CompoundTag) parsed.save(registries);
        helper.assertValueEqual(saved.getString("id"), "folkways_core:tag_filter", "stack writes current id");
        helper.assertValueEqual(saved.getCompound("components").getString("folkways_core:filter_tag"),
            "folkways_vanilla:resident_food", "stack writes current component contents");
        helper.succeed();
    }
}
