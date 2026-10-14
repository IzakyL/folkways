package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.ClimbableBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentNodeEvaluatorGameTests {

    private static final double STEP = 0.6D;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentNodeEvaluatorGameTests.class);
    }

    // Vanilla walks through any block short of a full cube unless the block says otherwise. A modded machine a
    // pixel short of the cube that does not say so stood in a resident's way for good (issue #9): every such block
    // in the pack is a wall to walk round and a floor to stand on.
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aBlockTooTallToStepIntoIsAWallNotAir(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(2, 1, 2));
        for (BlockPos floor : BlockPos.betweenClosed(cell.offset(-2, -1, -2), cell.offset(2, -1, 2))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        var mob = EntityType.VILLAGER.create(level);
        mob.setPos(Vec3.atBottomCenterOf(cell.offset(-2, 0, 0)));

        List<String> walkedThrough = new ArrayList<>();
        int tried = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (!passedOverByVanilla(state)) {
                continue;
            }
            level.setBlock(cell, state, Block.UPDATE_CLIENTS);
            level.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            try {
                if (!level.getBlockState(cell).is(block)
                        || state.getCollisionShape(level, cell).max(Direction.Axis.Y) <= STEP) {
                    continue;
                }
                var context = new PathfindingContext(level, mob);
                PathType vanilla = WalkNodeEvaluator.getPathTypeStatic(context, cell.mutable());
                if (vanilla != PathType.OPEN && vanilla != PathType.WALKABLE) {
                    continue;
                }
                tried++;
                var evaluator = new ResidentNodeEvaluator(cell.offset(-2, 0, 0));
                PathType in = evaluator.getPathType(context, cell.getX(), cell.getY(), cell.getZ());
                PathType on = evaluator.getPathType(context, cell.getX(), cell.getY() + 1, cell.getZ());
                if (in != PathType.BLOCKED || on != PathType.WALKABLE) {
                    walkedThrough.add(BuiltInRegistries.BLOCK.getKey(block) + " in=" + in + " on=" + on);
                }
            } finally {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        helper.assertTrue(tried > 0, "the pack holds no block a pixel short of the cube that vanilla walks through;"
            + " the test has nothing to try");
        helper.assertTrue(walkedThrough.isEmpty(), "residents walk through " + walkedThrough);
        helper.succeed();
    }

    // Blocks with entities and climbables are left out: a machine wants its block entity set up to stand at all,
    // and a ladder is walked in by design.
    private static boolean passedOverByVanilla(BlockState state) {
        if (state.isAir() || state.liquid() || state.getBlock() instanceof EntityBlock
                || ClimbableBlocks.isClimbable(state) || ClimbableBlocks.offersFloor(state)) {
            return false;
        }
        try {
            VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return !shape.isEmpty() && shape.max(Direction.Axis.Y) > STEP
                && state.isPathfindable(PathComputationType.LAND);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
