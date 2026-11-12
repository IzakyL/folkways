package io.github.izakyl.folkways.core.base.persist.upgrade;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysDataComponents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class DiskContainerUpgradeGameTests {
    private DiskContainerUpgradeGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if (!Boolean.getBoolean("folkways.core.integrationTests")) {
            event.register(DiskContainerUpgradeGameTests.class);
        }
    }

    private static RegionStorageInfo info(GameTestHelper helper) {
        return new RegionStorageInfo("namespace-container-test", helper.getLevel().dimension(), "entities");
    }

    private static ProtoChunk proto(GameTestHelper helper) {
        var level = helper.getLevel();
        return new ProtoChunk(new ChunkPos(helper.absolutePos(BlockPos.ZERO)), UpgradeData.EMPTY, level,
            level.registryAccess().registryOrThrow(Registries.BIOME), null);
    }

    private static CompoundTag legacyBox() throws Exception {
        return TagParser.parseTag("""
            {id:"minecraft:shulker_box",count:1,components:{"minecraft:container":[
              {slot:0,item:{id:"folkways:tag_filter",count:1,
                components:{"folkways:filter_tag":"folkways:resident_food"}}},
              {slot:1,item:{id:"folkways:colony_book",count:1,components:{"folkways:book_gesture":"line"}}}]}}
            """);
    }

    private static ListTag legacyInventory() throws Exception {
        CompoundTag box = legacyBox();
        ListTag inventory = new ListTag();
        box.putByte("Slot", (byte) 0);
        inventory.add(box);
        var nested = box.getCompound("components").getList("minecraft:container", Tag.TAG_COMPOUND);
        for (int index = 0; index < nested.size(); index++) {
            CompoundTag item = nested.getCompound(index).getCompound("item").copy();
            item.putByte("Slot", (byte) (index + 1));
            inventory.add(item);
        }
        return inventory;
    }

    private static void assertInventory(GameTestHelper helper, Container inventory) throws Exception {
        assertBox(helper, inventory.getItem(0));
        var nested = inventory.getItem(0).get(DataComponents.CONTAINER);
        for (int index = 0; index < 2; index++) {
            helper.assertTrue(ItemStack.isSameItemSameComponents(inventory.getItem(index + 1),
                nested.getStackInSlot(index)), "direct and nested legacy components normalize identically");
        }
    }

    private static void assertBox(GameTestHelper helper, ItemStack box) throws Exception {
        var registries = helper.getLevel().registryAccess();
        var contents = box.get(DataComponents.CONTAINER);
        helper.assertTrue(contents != null, "nested inventory decodes");
        ItemStack filter = contents.getStackInSlot(0);
        helper.assertValueEqual(BuiltInRegistries.ITEM.getKey(filter.getItem()).toString(),
            "folkways_core:tag_filter", "nested item alias");
        helper.assertTrue(filter.getCount() == 1, "nested count preserved");
        CompoundTag saved = (CompoundTag) filter.save(registries);
        helper.assertValueEqual(saved.getCompound("components").getString("folkways_core:filter_tag"),
            "folkways_vanilla:resident_food", "component codec normalizes its string payload");
        ItemStack book = contents.getStackInSlot(1);
        helper.assertValueEqual(BuiltInRegistries.ITEM.getKey(book.getItem()).toString(),
            "folkways_core:colony_book", "nested book alias");
        helper.assertValueEqual(((CompoundTag) book.save(registries)).getCompound("components")
            .getString("folkways_core:book_gesture"), "line", "book gesture preserved");
        CompoundTag current = TagParser.parseTag(legacyBox().toString()
            .replace("folkways:tag_filter", "folkways_core:tag_filter")
            .replace("folkways:filter_tag", "folkways_core:filter_tag")
            .replace("folkways:resident_food", "folkways_vanilla:resident_food")
            .replace("folkways:colony_book", "folkways_core:colony_book")
            .replace("folkways:book_gesture", "folkways_core:book_gesture"));
        ItemStack fresh = ItemStack.parse(registries, current).orElseThrow();
        helper.assertTrue(ItemStack.isSameItemSameComponents(box, fresh), "migration preserves stacking equality");
        helper.assertValueEqual(box.save(registries), fresh.save(registries), "encoding adds no migration metadata");
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void legacyChunksDecodePaletteBlockEntitiesAndNestedInventory(GameTestHelper helper)
            throws Exception {
        var level = helper.getLevel();
        var chunk = proto(helper);
        BlockPos pos = helper.absolutePos(BlockPos.ZERO);
        BlockPos chestPos = pos.above();
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("folkways_vanilla:creative_supply_box"));
        chunk.setBlockState(pos, block.defaultBlockState(), false);
        chunk.setBlockState(chestPos, Blocks.CHEST.defaultBlockState(), false);
        for (boolean stamped : List.of(false, true)) {
            CompoundTag raw = ChunkSerializer.write(level, chunk);
            for (Tag section : raw.getList("sections", Tag.TAG_COMPOUND)) {
                for (Tag entry : ((CompoundTag) section).getCompound("block_states").getList("palette", 10)) {
                    CompoundTag palette = (CompoundTag) entry;
                    if (palette.getString("Name").equals("folkways_vanilla:creative_supply_box")) {
                        palette.putString("Name", "folkways:creative_supply_box");
                    }
                }
            }
            CompoundTag supply = TagParser.parseTag("""
                {id:"folkways:creative_supply_box",all:true,revision:0L,generated:{}}
                """);
            supply.putInt("x", pos.getX());
            supply.putInt("y", pos.getY());
            supply.putInt("z", pos.getZ());
            if (stamped) {
                supply.put("NeoForgeData", TagParser.parseTag("{\"folkways:data_version\":2}"));
            }
            CompoundTag chest = TagParser.parseTag("{id:\"minecraft:chest\"}");
            chest.putInt("x", chestPos.getX());
            chest.putInt("y", chestPos.getY());
            chest.putInt("z", chestPos.getZ());
            chest.put("Items", legacyInventory());
            var blockEntities = raw.getList("block_entities", Tag.TAG_COMPOUND);
            blockEntities.add(supply);
            blockEntities.add(chest);
            var loaded = ChunkSerializer.read(level, level.getPoiManager(), info(helper), chunk.getPos(), raw);
            helper.assertValueEqual(loaded.getBlockState(pos), block.defaultBlockState(), "legacy palette resolves");
            BlockEntity restored = BlockEntity.loadStatic(pos, loaded.getBlockState(pos),
                loaded.getBlockEntityNbt(pos), level.registryAccess());
            helper.assertTrue(restored != null, "legacy block-entity type resolves before its migration hook");
            var inventory = (ChestBlockEntity) BlockEntity.loadStatic(chestPos, loaded.getBlockState(chestPos),
                loaded.getBlockEntityNbt(chestPos), level.registryAccess());
            helper.assertTrue(inventory != null, "chest inventory loads");
            assertInventory(helper, inventory);
            helper.assertValueEqual(restored.saveWithFullMetadata(level.registryAccess())
                .getCompound("NeoForgeData").getInt(RawUpgrades.VERSION), DataVersion.CURRENT,
                "legacy block entities save at the current Folkways version");
            loaded.setBlockEntity(restored);
            loaded.setBlockEntity(inventory);
            CompoundTag saved = ChunkSerializer.write(level, loaded);
            helper.assertFalse(saved.toString().contains("folkways:"), "saved chunk uses only current ids");
        }
        helper.succeed();
    }

    private static Path directory() throws IOException {
        return Files.createTempDirectory(Files.createDirectories(Path.of("build")), "namespace-containers-");
    }

    private static void cleanup(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void legacyEntityRegionsDecodeTypesAndNestedItems(GameTestHelper helper) throws Exception {
        Path directory = directory();
        var pos = new ChunkPos(0, 0);
        var region = new SimpleRegionStorage(info(helper), directory, DataFixers.getDataFixer(), false,
            DataFixTypes.ENTITY_CHUNK);
        try (var entities = new EntityStorage(region, helper.getLevel(), Runnable::run)) {
            for (boolean stamped : List.of(false, true)) {
                var resident = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("folkways:resident"))
                    .create(helper.getLevel());
                CompoundTag oldResident = resident.saveWithoutId(new CompoundTag());
                oldResident.putString("id", "folkways:resident");
                oldResident.getCompound("NeoForgeData").remove(RawUpgrades.VERSION);
                if (stamped) {
                    oldResident.getCompound("NeoForgeData").putInt("folkways:data_version", 2);
                }
                var item = new ItemEntity(helper.getLevel(), 0, 0, 0, ItemStack.EMPTY);
                CompoundTag oldItem = item.saveWithoutId(new CompoundTag());
                oldItem.putString("id", "minecraft:item");
                oldItem.put("Item", legacyBox());
                CompoundTag raw = new CompoundTag();
                raw.putIntArray("Position", new int[] {0, 0});
                raw.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
                var entries = new ListTag();
                entries.add(oldResident);
                entries.add(oldItem);
                raw.put("Entities", entries);
                region.write(pos, raw).join();
                var loaded = entities.loadEntities(pos).join();
                var decoded = loaded.getEntities().toList();
                helper.assertTrue(decoded.size() == 2, "both legacy entities survive region loading");
                helper.assertValueEqual(BuiltInRegistries.ENTITY_TYPE.getKey(decoded.get(0).getType()).toString(),
                    "folkways_vanilla:resident", "legacy entity type alias");
                helper.assertValueEqual(decoded.getFirst().saveWithoutId(new CompoundTag())
                    .getCompound("NeoForgeData").getInt(RawUpgrades.VERSION), DataVersion.CURRENT,
                    "legacy residents save at the current Folkways version");
                assertBox(helper, ((ItemEntity) decoded.get(1)).getItem());
                entities.storeEntities(loaded);
                CompoundTag saved = region.read(pos).join().orElseThrow();
                helper.assertFalse(saved.toString().contains("folkways:"), "saved region uses current ids");
                decoded.forEach(Entity::discard);
                resident.discard();
            }
        } finally {
            cleanup(directory);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void legacyPlayerDiskAndIntegratedInventoriesDecode(GameTestHelper helper) throws Exception {
        Path directory = directory();
        try (var access = LevelStorageSource.createDefault(directory).createAccess("world")) {
            var storage = new PlayerDataStorage(access, DataFixers.getDataFixer());
            var player = helper.makeMockPlayer(GameType.SURVIVAL);
            Path file = storage.getPlayerDir().toPath().resolve(player.getStringUUID() + ".dat");
            for (boolean stamped : List.of(false, true)) {
                CompoundTag raw = player.saveWithoutId(new CompoundTag());
                raw.getCompound("NeoForgeData").remove(RawUpgrades.VERSION);
                if (stamped) {
                    raw.put("NeoForgeData", TagParser.parseTag("{\"folkways:data_version\":2}"));
                }
                raw.put("Inventory", legacyInventory());
                NbtIo.writeCompressed(raw, file);
                storage.load(player).orElseThrow();
                assertInventory(helper, player.getInventory());
                storage.save(player);
                CompoundTag saved = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                helper.assertFalse(saved.toString().contains("folkways:"), "playerdata saves current ids");
                // PlayerList loads the integrated owner's level.dat payload through Player.load as well.
                var integrated = helper.makeMockPlayer(GameType.SURVIVAL);
                integrated.load(raw.copy());
                assertInventory(helper, integrated.getInventory());
            }
        } finally {
            cleanup(directory);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void directStackCodecsResolveAliasesWithoutRewritingOpaqueData(GameTestHelper helper)
            throws Exception {
        var registries = helper.getLevel().registryAccess();
        var ops = registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        for (var codec : List.of(ItemStack.CODEC, ItemStack.SINGLE_ITEM_CODEC, ItemStack.OPTIONAL_CODEC,
                ItemStack.STRICT_CODEC)) {
            CompoundTag raw = legacyBox();
            CompoundTag before = raw.copy();
            assertBox(helper, codec.parse(ops, raw).getOrThrow());
            helper.assertValueEqual(raw, before, "decoding does not mutate the raw container");
            CompoundTag removal = TagParser.parseTag("""
                {id:"minecraft:stone",count:1,components:{"!folkways:book_gesture":{},
                  "folkways:filter_tag":"folkways:resident_food","minecraft:custom_data":{
                    opaque:{id:"folkways:colony_book"}}}}
                """);
            ItemStack loaded = codec.parse(ops, removal).getOrThrow();
            CompoundTag saved = (CompoundTag) loaded.save(registries);
            var patch = DataComponentPatch.CODEC.parse(ops, removal.getCompound("components")).getOrThrow();
            CompoundTag encoded = (CompoundTag) DataComponentPatch.CODEC.encodeStart(ops, patch).getOrThrow();
            helper.assertTrue(encoded.contains("!folkways_core:book_gesture"),
                "registry alias resolves removed component keys before stack sanitization");
            helper.assertFalse(loaded.has(FolkwaysDataComponents.BOOK_GESTURE.get()), "removed gesture is absent");
            helper.assertValueEqual(saved.getCompound("components").getString("folkways_core:filter_tag"),
                "folkways_vanilla:resident_food", "direct codec normalizes the component payload");
            helper.assertValueEqual(saved.getCompound("components").getCompound("minecraft:custom_data")
                .getCompound("opaque").getString("id"), "folkways:colony_book", "opaque component data preserved");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void componentCodecsNormalizeJsonAndPreserveUnknownIds(GameTestHelper helper) {
        var filter = FolkwaysDataComponents.FILTER_TAG.get().codec();
        var normalized = filter.parse(JsonOps.INSTANCE, new JsonPrimitive("folkways:resident_food")).getOrThrow();
        helper.assertValueEqual(normalized.toString(), "folkways_vanilla:resident_food", "JSON string id normalizes");
        helper.assertValueEqual(filter.encodeStart(JsonOps.INSTANCE, normalized).getOrThrow(),
            new JsonPrimitive("folkways_vanilla:resident_food"), "JSON encoder writes the current value unchanged");
        var unknown = filter.parse(JsonOps.INSTANCE, new JsonPrimitive("folkways:unknown_component_test")).getOrThrow();
        helper.assertValueEqual(unknown.toString(), "folkways:unknown_component_test", "unknown exact ids preserved");
        var book = FolkwaysDataComponents.BOOK_GESTURE.get().codec();
        var gesture = book.parse(JsonOps.INSTANCE, new JsonPrimitive("box")).getOrThrow();
        helper.assertValueEqual(book.encodeStart(JsonOps.INSTANCE, gesture).getOrThrow(),
            new JsonPrimitive("box"), "released bare gesture values are unchanged");
        helper.assertTrue(book.parse(JsonOps.INSTANCE, new JsonPrimitive("unknown")).result().isEmpty(),
            "the value codec still rejects invalid gestures");
        helper.succeed();
    }

}
