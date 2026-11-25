package io.github.izakyl.folkways.vanilla.cookery.teapot;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.TeapotBlockEntity;
import io.github.izakyl.folkways.core.base.persist.upgrade.RawUpgrades;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysDataComponents;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.shell.chiselsandbits.CandBConfig;
import io.github.izakyl.folkways.core.shell.create.FolkwaysCreateConfig;
import io.github.izakyl.folkways.vanilla.tacz.TaczConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_save_upgrade")
@PrefixGameTestTemplate(false)
public final class ReleasedSaveGameTests {
    private static final BlockPos TEAPOT = new BlockPos(5, -59, 5);
    private static final ChunkPos CHUNK = new ChunkPos(TEAPOT);
    private static final Pattern LEGACY = Pattern.compile(
        "folkways_(?:golems|epicfight|carving|cookery|carts|firearms|create):|folkways_vanilla:station_seat");

    private ReleasedSaveGameTests() {
    }

    @GameTest(template = "empty")
    public static void upgradesReleasedV4World(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var server = level.getServer();
        Path world = server.getWorldPath(LevelResource.ROOT);
        Path colonies = world.resolve("data/folkways_colonies.dat");
        CompoundTag released = NbtIo.readCompressed(colonies, NbtAccounter.unlimitedHeap()).getCompound("data");
        helper.assertValueEqual(released.getInt("schemaVersion"), 4, "fixture colony registry is from V4");
        UUID owner = released.getList("colonies", Tag.TAG_COMPOUND).getCompound(0).getUUID("colonyId");

        var colony = Colonies.of(server, owner).orElseThrow();
        helper.assertValueEqual(Colonies.all(server).size(), 1, "one released colony");
        var lines = ColonyFront.of(colony).paths();
        helper.assertValueEqual(lines.size(), 1, "the colony retains its cart route");
        var line = lines.getFirst();
        helper.assertValueEqual(line.delegation(), id("folkways_vanilla:cart_line"), "cart route delegation");
        helper.assertValueEqual(line.points(), List.of(TEAPOT.west(2), TEAPOT.west(4)), "cart route points");
        helper.assertValueEqual(line.settings().valueOf("round").orElseThrow(),
            new ColonySettings.Value.Choice(id("folkways_vanilla:line_open")), "cart route setting");

        level.getChunk(CHUNK.x, CHUNK.z);
        var pot = (TeapotBlockEntity) level.getBlockEntity(TEAPOT);
        var batch = TeaBatch.read(pot, level).orElseThrow();
        helper.assertValueEqual(batch.colony(), owner, "teapot reservation belongs to the colony");
        helper.assertValueEqual(batch.recipe(), TeapotStation.MYSTERY, "procedural recipe migrated");
        helper.assertTrue(batch.loaded(), "teapot retains the reserved ingredient");
        helper.assertValueEqual(batch.output().getCount(), 4, "reserved mystery tea portions survive");
        var registries = level.registryAccess();
        assertVersion(helper, pot.saveWithFullMetadata(registries));

        var counter = level.getBlockEntity(TEAPOT.east(3));
        helper.assertValueEqual(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(counter.getType()),
            id("folkways_vanilla:tavern_counter"), "tavern counter block entity registry alias");
        assertVersion(helper, counter.saveWithFullMetadata(registries));

        var carving = (ChiseledBlockEntity) level.getBlockEntity(TEAPOT.east(4));
        helper.assertValueEqual(carving.payload().storage().getBlockInformation(1, 0, 0).blockState(),
            Blocks.STONE.defaultBlockState(), "the carved stone voxel survives");

        var chest = (ChestBlockEntity) level.getBlockEntity(TEAPOT.north(4));
        var filters = List.of("folkways_core:melee", "folkways_vanilla:pavilion",
            "folkways_vanilla:cookery_sickles", "folkways_core:warfare/firearms");
        for (int slot = 0; slot < filters.size(); slot++) {
            var stack = chest.getItem(slot);
            helper.assertValueEqual(stack.getCount(), 1, "filter stack count");
            helper.assertValueEqual(stack.get(FolkwaysDataComponents.FILTER_TAG.get()),
                id(filters.get(slot)), "filter component payload");
        }
        assertCurrent(helper, chest.saveWithFullMetadata(registries));

        // Inspect the released passenger tree before behavior ticks can release transient seat reservations.
        assertPassengers(helper, colony);
        server.saveEverything(true, true, true);
        CompoundTag saved = NbtIo.readCompressed(colonies, NbtAccounter.unlimitedHeap()).getCompound("data");
        helper.assertValueEqual(saved.getInt("schemaVersion"), 5, "colony registry was upgraded and saved");
        assertCurrent(helper, saved);

        assertRenamed(helper, Path.of("config"), "folkways_create-server.toml", "folkways_core-create-server.toml");
        assertRenamed(helper, Path.of("config"), "folkways_firearms-server.toml", "folkways_core-tacz-server.toml");
        assertRenamed(helper, world.resolve("serverconfig"), "folkways_carving-server.toml",
            "folkways_core-chiselsandbits-server.toml");
        helper.assertValueEqual(FolkwaysCreateConfig.seatTicks(), 13, "Create config value survives");
        helper.assertValueEqual(TaczConfig.rangeCap(), 31.0D, "TACZ config value survives");
        helper.assertValueEqual(CandBConfig.MATERIALS.get(), 128, "world Chisels & Bits override survives");
        helper.succeed();
    }

    private static void assertPassengers(GameTestHelper helper, Colony colony) throws Exception {
        var level = helper.getLevel();
        var info = new RegionStorageInfo("released-v4", level.dimension(), "entities");
        var region = new SimpleRegionStorage(info, Path.of("released-entities"), DataFixers.getDataFixer(), false,
            DataFixTypes.ENTITY_CHUNK);
        try (var storage = new EntityStorage(region, level, Runnable::run)) {
            var loaded = storage.loadEntities(CHUNK).join();
            var roots = loaded.getEntities().toList();
            var hull = named(roots, "Hull");
            var crew = named(hull.getPassengers(), "Crew");
            var assignment = hull.getPersistentData().getCompound("folkways_core:vehicle_crew");
            helper.assertValueEqual(assignment.getUUID("owner"), colony.id(), "the hull retains its crew's colony");
            helper.assertValueEqual(assignment.getUUID("seat0"), crew.getUUID(), "hull seat assignment");
            var seat = crew.getPersistentData().getCompound("folkways_core:vehicle_seat");
            helper.assertValueEqual(seat.getUUID("vehicle"), hull.getUUID(), "crew seat links back to hull");
            helper.assertValueEqual(seat.getInt("seat"), 0, "crew seat index");

            var mortar = named(roots, "Station");
            var sentry = named(roots, "Sentry");
            var marker = sentry.getPersistentData().getCompound("folkways_core:seat_stance");
            helper.assertValueEqual(marker.getUUID("token"), UUID.fromString("413303a3-e187-4be6-8d40-c6d491ced733"),
                "sentry seat lease becomes stance token");
            helper.assertValueEqual(marker.getUUID("entity"), mortar.getUUID(), "sentry stance retains vehicle UUID");

            var trainSeat = named(roots, "Train seat");
            var rider = named(trainSeat.getPassengers(), "Rider");
            helper.assertValueEqual(rider.getPersistentData().getString("folkways_core:ride_destination"),
                "North Station", "Create rider retains the destination");
            var golem = named(roots, "Golem");
            var cook = named(roots, "Cook");
            for (Entity resident : List.of(golem, cook, crew, sentry, rider)) {
                helper.assertValueEqual(Bodies.of(resident).orElseThrow().colonyId().orElseThrow(), colony.id(),
                    "resident attachment retains membership for " + resident.getName().getString());
                helper.assertValueEqual(resident.getPersistentData().getInt(RawUpgrades.VERSION), 5,
                    "loaded resident has V5 data for " + resident.getName().getString());
            }
            helper.assertValueEqual(colony.claimOf(golem.getUUID()).orElseThrow().owner(),
                id("folkways_core:golem_body"), "golem membership claim migrated");
            helper.assertValueEqual(BuiltInRegistries.ENTITY_TYPE.getKey(named(roots, "Cart").getType()),
                id("astikorcartsredux:supply_cart"), "native cart entity survives");

            storage.storeEntities(loaded);
            CompoundTag saved = region.read(CHUNK).join().orElseThrow();
            assertCurrent(helper, saved);
            for (Tag tag : saved.getList("Entities", Tag.TAG_COMPOUND)) {
                assertOwnedVersions(helper, (CompoundTag) tag);
            }
        }
    }

    private static Entity named(List<Entity> entities, String name) {
        return entities.stream().filter(entity -> entity.getName().getString().equals(name)).findFirst().orElseThrow();
    }

    private static void assertOwnedVersions(GameTestHelper helper, CompoundTag entity) {
        if (entity.getCompound("NeoForgeData").contains(RawUpgrades.VERSION)) {
            assertVersion(helper, entity);
        }
        for (Tag passenger : entity.getList("Passengers", Tag.TAG_COMPOUND)) {
            assertOwnedVersions(helper, (CompoundTag) passenger);
        }
    }

    private static void assertVersion(GameTestHelper helper, CompoundTag saved) {
        helper.assertValueEqual(saved.getCompound("NeoForgeData").getInt(RawUpgrades.VERSION), 5,
            "Folkways data version for " + saved.getString("id"));
        assertCurrent(helper, saved);
    }

    private static void assertCurrent(GameTestHelper helper, CompoundTag saved) {
        var legacy = LEGACY.matcher(saved.toString());
        helper.assertFalse(legacy.find(), "the resaved data must contain only current IDs");
    }

    private static void assertRenamed(GameTestHelper helper, Path folder, String released, String current) {
        helper.assertFalse(Files.exists(folder.resolve(released)), "old config filename removed: " + released);
        helper.assertTrue(Files.isRegularFile(folder.resolve(current)), "renamed config exists: " + current);
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }
}
