package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class SiteApproachGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SiteApproachGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void changedCellsCannotHoldTheWorkerOrItsFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos feet = helper.absolutePos(new BlockPos(8, 2, 8));
        level.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(SiteApproach.stands(level, feet, Set.of()), "the intact floor holds a worker");
        for (BlockPos changed : List.of(feet, feet.above(), feet.below())) {
            helper.assertTrue(!SiteApproach.stands(level, feet, Set.of(changed)),
                "a piece must not occupy its worker or remove its floor");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void scaffoldRungsAndTopsAreUsefulFootings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos foot = helper.absolutePos(new BlockPos(8, 2, 8));
        level.setBlock(foot.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(foot, ScaffoldAction.state(), 3);
        level.setBlock(foot.above(), ScaffoldAction.state(), 3);
        BlockPos top = foot.above(2);
        helper.assertTrue(SiteApproach.stands(level, foot, Set.of()), "a worker can climb inside scaffolding");
        helper.assertTrue(SiteApproach.stands(level, top, Set.of()), "a worker can stand on its top");
        BlockPos focus = top.offset(2, 0, 0);
        helper.assertTrue(SiteApproach.stances(level, focus, Set.of(focus), List.of(top)).getFirst().equals(top),
            "a completed scaffold gives a preferred working position near the target");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void stalePlannedFootingsAreReplacedByCurrentGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos focus = helper.absolutePos(new BlockPos(8, 2, 8));
        BlockPos old = focus.west(2);
        BlockPos current = focus.east(2);
        level.setBlock(current.below(), Blocks.STONE.defaultBlockState(), 3);
        List<BlockPos> stances = SiteApproach.stances(level, focus, Set.of(focus), List.of(old));
        helper.assertTrue(!stances.contains(old) && stances.contains(current),
            "the worker finds existing ground instead of trusting the old plan");
        level.setBlock(current.above(), Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(!SiteApproach.stances(level, focus, Set.of(focus), List.of(current)).contains(current),
            "a footing closed in by later work is no longer offered");
        helper.succeed();
    }
}
