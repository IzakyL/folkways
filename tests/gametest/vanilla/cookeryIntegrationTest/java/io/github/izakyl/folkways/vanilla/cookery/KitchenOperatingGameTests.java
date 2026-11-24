package io.github.izakyl.folkways.vanilla.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class KitchenOperatingGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(KitchenOperatingGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void eachRotatedKitchenApplianceOffersOnlyItsAdjacentOperatingColumn(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos target = helper.absolutePos(new BlockPos(3, 2, 3));
        for (BlockPos floor : BlockPos.betweenClosed(target.offset(-3, -2, -3), target.offset(3, -2, 3))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
        WorldPos at = WorldPos.of(level, target);
        for (var block : List.of(ModBlocks.CHOPPING_BOARD.get(), ModBlocks.STOCKPOT.get(), ModBlocks.POT.get(),
                ModBlocks.STEAMER.get(), ModBlocks.SHAWARMA_SPIT.get(), ModBlocks.STOVE.get())) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                var state = block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
                level.setBlockAndUpdate(target, state);
                var station = KitchenStations.find(state).orElseThrow();
                Direction handle = block == ModBlocks.STOCKPOT.get() || block == ModBlocks.STEAMER.get()
                    ? facing.getClockWise() : facing;
                BlockPos feet = target.relative(handle).below();
                helper.assertTrue(station.stances(level, at).orElseThrow().cells().equals(Set.of(WorldPos.of(level, feet))),
                    "only the physical adjacent operating column is offered for " + block + " / " + facing);
                level.setBlockAndUpdate(feet, Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(feet.above(), Blocks.STONE.defaultBlockState());
                helper.assertTrue(station.stances(level, at).isEmpty(),
                    "blocked handle cannot fall back to a remote or opposite-side work position");
                level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(feet.below(), Blocks.AIR.defaultBlockState());
                helper.assertTrue(station.stances(level, at).isEmpty(),
                    "an unsupported handle approach cannot borrow any of the other reachable floors: " + block + " / " + facing);
                level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
            }
        }
        helper.succeed();
    }
}
