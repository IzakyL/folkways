package io.github.izakyl.folkways.core.world.api;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FootingGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FootingGameTests.class);
    }

    @GameTest(template = "empty")
    public static void taggedTopsOfferNoPlaceToStand(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(2, 1, 2));
        List<BlockState> floors = List.of(
            Blocks.BAMBOO.defaultBlockState(),
            Blocks.OAK_FENCE.defaultBlockState(),
            connected(Blocks.OAK_FENCE.defaultBlockState()),
            Blocks.COBBLESTONE_WALL.defaultBlockState(),
            Blocks.OAK_FENCE_GATE.defaultBlockState(),
            Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.OPEN, true),
            Blocks.IRON_BARS.defaultBlockState(),
            connected(Blocks.IRON_BARS.defaultBlockState()),
            Blocks.GLASS_PANE.defaultBlockState(),
            connected(Blocks.GLASS_PANE.defaultBlockState()),
            Blocks.BLACK_STAINED_GLASS_PANE.defaultBlockState(),
            Blocks.CHAIN.defaultBlockState(),
            Blocks.CHAIN.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
            Blocks.END_ROD.defaultBlockState(),
            Blocks.END_ROD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST),
            Blocks.LIGHTNING_ROD.defaultBlockState(),
            Blocks.CANDLE.defaultBlockState(),
            Blocks.POINTED_DRIPSTONE.defaultBlockState());
        for (BlockState state : floors) {
            level.setBlock(floor, state, Block.UPDATE_CLIENTS);
            helper.assertTrue(Footing.forbidsStandingAbove(state), "the no-footing tag covers " + state);
            helper.assertTrue(!Footing.holdsAbove(level, floor), "a tagged top offers no floor: " + state);
            helper.assertTrue(!Footing.withFeetAt(level, floor.above()), "no job stands on top of " + state);
        }
        for (int candles = 1; candles <= 4; candles++) {
            BlockState state = Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, candles);
            level.setBlock(floor, state, Block.UPDATE_CLIENTS);
            helper.assertTrue(!Footing.withFeetAt(level, floor.above()), "nobody stands on top of candles");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ordinaryTopsAndStairTreadsHoldSomeoneUp(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(2, 1, 2));
        // A top slab is kept as a dining table; this view asks only whether its shape holds someone up.
        var blocks = Structures.region(level, floor.offset(-1, -1, -1), floor.offset(1, 3, 1));
        List<BlockState> floors = List.of(
            Blocks.DIRT.defaultBlockState(),
            Blocks.FARMLAND.defaultBlockState(),
            Blocks.DIRT_PATH.defaultBlockState(),
            Blocks.SOUL_SAND.defaultBlockState(),
            Blocks.CHEST.defaultBlockState(),
            Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP),
            Blocks.HOPPER.defaultBlockState(),
            Blocks.CAULDRON.defaultBlockState(),
            Blocks.COMPOSTER.defaultBlockState(),
            Blocks.SCAFFOLDING.defaultBlockState());
        for (BlockState state : floors) {
            level.setBlock(floor, state, Block.UPDATE_CLIENTS);
            helper.assertTrue(Footing.holdsAbove(level, floor), "an ordinary top offers a floor: " + state);
            helper.assertTrue(Footing.withFeetAt(blocks, floor.above()), "an ordinary top holds someone up: " + state);
        }
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (Half half : Half.values()) {
                for (StairsShape shape : StairsShape.values()) {
                    BlockState state = Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                        .setValue(BlockStateProperties.HALF, half)
                        .setValue(BlockStateProperties.STAIRS_SHAPE, shape);
                    level.setBlock(floor, state, Block.UPDATE_CLIENTS);
                    helper.assertTrue(Footing.withFeetAt(blocks, floor.above()),
                        "a stair tread holds a body: " + state);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void lowerSlabsKeepTheirFeetInsideTheBlockCell(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos slab = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlock(slab.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(slab, Blocks.STONE_SLAB.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.assertTrue(!Footing.withFeetAt(level, slab), "job footings still reject an occupied slab cell");
        helper.assertTrue(!Footing.withFeetAt(level, slab.above()), "a lower slab stops short of the cell above");
        level.setBlock(slab, Blocks.OAK_STAIRS.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.assertTrue(!Footing.withFeetAt(level, slab), "a stair's lower tread is also inside its occupied cell");
        helper.assertTrue(Footing.withFeetAt(level, slab.above()), "a stair's upper tread holds the cell above");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void taggedTopsDoNotBecomeFootingsInsideAClimb(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos feet = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(feet, Blocks.LADDER.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.assertTrue(Footing.withFeetAt(level, feet), "a ladder holds someone in its own cell");
        level.setBlock(feet.below(), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.assertTrue(!Footing.withFeetAt(level, feet), "a rung cannot make room inside a fence post");
        level.setBlock(feet.below(), Blocks.BAMBOO.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.assertTrue(!Footing.withFeetAt(level, feet), "a rung above bamboo is still over a forbidden top");
        helper.succeed();
    }

    private static BlockState connected(BlockState state) {
        return state.setValue(BlockStateProperties.NORTH, true).setValue(BlockStateProperties.EAST, true)
            .setValue(BlockStateProperties.SOUTH, true).setValue(BlockStateProperties.WEST, true);
    }
}
