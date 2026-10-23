package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ApiaryCampfiresGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ApiaryCampfiresGameTests.class);
    }

    @GameTest(template = "empty")
    public static void campfireIsRequestedOnGroundBelowHive(GameTestHelper helper) {
        BlockPos hive = fixture(helper);
        helper.assertTrue(ApiaryCampfires.site(helper.getLevel(), hive).filter(hive.below(3)::equals).isPresent(),
            "campfire is placed on ground, rather than floating immediately below the hive");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void effectiveSmokeNeedsNoConstruction(GameTestHelper helper) {
        BlockPos hive = fixture(helper);
        helper.getLevel().setBlock(hive.below(3), Blocks.CAMPFIRE.defaultBlockState(), 2);
        helper.getLevel().setBlock(hive.below(2), Blocks.WHITE_CARPET.defaultBlockState(), 2);
        helper.assertTrue(CampfireBlock.isSmokeyPos(helper.getLevel(), hive), "carpet retains vanilla smoke protection");
        helper.assertTrue(ApiaryCampfires.site(helper.getLevel(), hive).isEmpty(), "existing effective smoke is reused");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void campfiresNeverExcavateOrReplaceWater(GameTestHelper helper) {
        BlockPos hive = fixture(helper);
        helper.getLevel().setBlock(hive.below(3), Blocks.WATER.defaultBlockState(), 2);
        helper.assertTrue(ApiaryCampfires.site(helper.getLevel(), hive).isEmpty(), "water is preserved");
        helper.getLevel().setBlock(hive.below(3), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(hive.below(2), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(hive.below(), Blocks.STONE.defaultBlockState(), 2);
        helper.assertTrue(ApiaryCampfires.site(helper.getLevel(), hive).isEmpty(), "solid supporting blocks are preserved");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void distantGroundDoesNotRequestUselessCampfire(GameTestHelper helper) {
        BlockPos hive = helper.absolutePos(new BlockPos(2, 7, 2));
        for (int below = 1; below <= 6; below++) {
            helper.getLevel().setBlock(hive.below(below), Blocks.AIR.defaultBlockState(), 2);
        }
        helper.getLevel().setBlock(hive.below(7), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(hive, Blocks.BEEHIVE.defaultBlockState(), 2);
        helper.assertTrue(ApiaryCampfires.site(helper.getLevel(), hive).isEmpty(), "ground beyond smoke range is skipped");
        helper.succeed();
    }

    private static BlockPos fixture(GameTestHelper helper) {
        BlockPos hive = helper.absolutePos(new BlockPos(2, 5, 2));
        helper.getLevel().setBlock(hive, Blocks.BEEHIVE.defaultBlockState(), 2);
        for (int below = 1; below <= 3; below++) {
            helper.getLevel().setBlock(hive.below(below), Blocks.AIR.defaultBlockState(), 2);
        }
        helper.getLevel().setBlock(hive.below(4), Blocks.STONE.defaultBlockState(), 2);
        return hive;
    }
}
