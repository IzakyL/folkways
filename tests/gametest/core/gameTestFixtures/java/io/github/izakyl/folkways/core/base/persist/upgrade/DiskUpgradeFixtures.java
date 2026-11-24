package io.github.izakyl.folkways.core.base.persist.upgrade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/**
 * Disk upgrade helpers shared with other suites. Kept out of a {@code *GameTests} class so focused runs, which drop
 * unselected GameTest classes from the fixtures, still compile the suites that call them.
 */
public final class DiskUpgradeFixtures {
    private DiskUpgradeFixtures() {
    }

    /** Reuse the region fixture for the Create profile's release-format rider. */
    public static void versionFourRiderRoundTrip(GameTestHelper helper) throws Exception {
        Path directory = directory();
        var pos = new ChunkPos(0, 0);
        var region = new SimpleRegionStorage(info(helper), directory, DataFixers.getDataFixer(), false,
            DataFixTypes.ENTITY_CHUNK);
        try (var entities = new EntityStorage(region, helper.getLevel(), Runnable::run)) {
            var resident = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("folkways_vanilla:resident"))
                .create(helper.getLevel());
            CompoundTag oldResident = resident.saveWithoutId(new CompoundTag());
            resident.discard();
            oldResident.putString("id", "folkways_vanilla:resident");
            oldResident.put("NeoForgeData", TagParser.parseTag("""
                {"folkways_core:data_version":4,"folkways_create:ride_destination":"North Station"}
                """));
            CompoundTag raw = new CompoundTag();
            raw.putIntArray("Position", new int[] {0, 0});
            raw.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            ListTag entries = new ListTag();
            entries.add(oldResident);
            raw.put("Entities", entries);
            region.write(pos, raw).join();

            var loaded = entities.loadEntities(pos).join();
            var rider = loaded.getEntities().findFirst().orElseThrow();
            helper.assertValueEqual(rider.getPersistentData().getString("folkways_core:ride_destination"),
                "North Station", "rider retains the station from its release-format save");
            entities.storeEntities(loaded);
            CompoundTag saved = region.read(pos).join().orElseThrow().getList("Entities", Tag.TAG_COMPOUND)
                .getCompound(0).getCompound("NeoForgeData");
            helper.assertValueEqual(saved.getInt(RawUpgrades.VERSION), DataVersion.CURRENT, "saved rider is stamped V5");
            helper.assertFalse(saved.contains("folkways_create:ride_destination"), "old rider key is gone");
            rider.discard();
        } finally {
            cleanup(directory);
        }
        helper.succeed();
    }

    static RegionStorageInfo info(GameTestHelper helper) {
        return new RegionStorageInfo("namespace-container-test", helper.getLevel().dimension(), "entities");
    }

    static Path directory() throws IOException {
        return Files.createTempDirectory(Files.createDirectories(Path.of("build")), "namespace-containers-");
    }

    static void cleanup(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

}
