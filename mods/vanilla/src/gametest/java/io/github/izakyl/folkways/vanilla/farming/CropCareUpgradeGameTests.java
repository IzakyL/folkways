package io.github.izakyl.folkways.vanilla.farming;

import io.github.izakyl.folkways.core.base.persist.upgrade.DataVersion;
import io.github.izakyl.folkways.core.base.persist.upgrade.RawUpgrades;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class CropCareUpgradeGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CropCareUpgradeGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void stampedAndUnstampedTreatmentsRoundTrip(GameTestHelper helper) {
        CropCare care = new CropCare();
        care.tend(BlockPos.ZERO, Blocks.WHEAT.defaultBlockState(), .3);
        var registries = helper.getLevel().registryAccess();
        CompoundTag saved = care.save(new CompoundTag(), registries);
        helper.assertTrue(saved.getInt(RawUpgrades.VERSION) == DataVersion.CURRENT, "CropCare writes global version");
        helper.assertValueEqual(CropCare.load(saved).save(new CompoundTag(), registries), saved,
            "stamped CropCare round trips");
        CompoundTag baseline = saved.copy();
        baseline.remove(RawUpgrades.VERSION);
        helper.assertValueEqual(CropCare.load(baseline).save(new CompoundTag(), registries), saved,
            "missing version means baseline");
        for (Tag version : List.of(IntTag.valueOf(DataVersion.BASELINE - 1), StringTag.valueOf("invalid"))) {
            CompoundTag unsupported = saved.copy();
            unsupported.put(RawUpgrades.VERSION, version);
            boolean rejected = false;
            try {
                CropCare.load(unsupported);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "unsupported CropCare versions are refused");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void futureTreatmentsArePreservedWithoutDecodingOrOverwriting(GameTestHelper helper) {
        CompoundTag future = new CompoundTag();
        future.putInt(RawUpgrades.VERSION, DataVersion.CURRENT + 1);
        future.putString("crops", "unknown future shape");
        CompoundTag unknown = new CompoundTag();
        unknown.putLong("future", 42);
        future.put("unknown", unknown);
        CompoundTag original = future.copy();
        CropCare care = CropCare.load(future);
        future.getCompound("unknown").putLong("future", 0);
        care.tend(BlockPos.ZERO, Blocks.WHEAT.defaultBlockState(), .3);
        care.forget(BlockPos.ZERO);
        care.setDirty();
        var registries = helper.getLevel().registryAccess();
        CompoundTag saved = care.save(new CompoundTag(), registries);
        helper.assertValueEqual(saved, original, "future payload survives dirty saves without business decoding");
        saved.getCompound("unknown").putLong("future", 1);
        helper.assertValueEqual(care.save(new CompoundTag(), registries), original,
            "saved tags cannot mutate the retained future payload");
        helper.succeed();
    }
}
