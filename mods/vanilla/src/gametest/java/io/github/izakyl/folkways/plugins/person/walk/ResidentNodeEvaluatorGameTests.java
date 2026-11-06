package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.ground.Grounds;
import io.github.izakyl.folkways.core.api.terms.ClimbableBlocks;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import io.github.izakyl.folkways.core.api.terms.ObstructedPathRegion;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.PathFinder;
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

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentNodeEvaluatorGameTests {

    private static final double STEP = 0.6D;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentNodeEvaluatorGameTests.class);
    }

    @GameTest(template = "empty")
    public static void escapingOneKeepoutDoesNotPermitEnteringAnother(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos other = start.offset(3, 0, 0);
        for (BlockPos floor : BlockPos.betweenClosed(start.offset(-1, -1, -1), other.offset(1, -1, 2))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        Runnable remove = Keepouts.install((host, x, y, z) -> host == level && start.equals(new BlockPos(x, y, z)));
        Runnable removeOther = Keepouts.install((host, x, y, z) -> host == level
            && (start.north().equals(new BlockPos(x, y, z)) || other.equals(new BlockPos(x, y, z))));
        try {
            var mob = EntityType.VILLAGER.create(level);
            mob.setPos(Vec3.atBottomCenterOf(start));
            var region = Structures.region(level, start.offset(-2, -2, -2), other.offset(2, 3, 3));
            var escape = new PathFinder(new ResidentNodeEvaluator(start), 512)
                .findPath(region, mob, Set.of(start.south()), 12, 0, 1);
            helper.assertTrue(escape != null && escape.canReach(), "a resident in a keepout can walk out");
            var shortcut = new PathFinder(new ResidentNodeEvaluator(start), 512)
                .findPath(region, mob, Set.of(other), 12, 0, 1);
            helper.assertTrue(shortcut == null || !shortcut.canReach(),
                "leaving one keepout never grants entry into a separate keepout");
            var adjacent = new PathFinder(new ResidentNodeEvaluator(start), 512)
                .findPath(region, mob, Set.of(start.north()), 12, 0, 1);
            helper.assertTrue(adjacent == null || !adjacent.canReach(),
                "an adjoining keepout from another source stays blocked during escape");
            helper.succeed();
        } finally {
            remove.run();
            removeOther.run();
        }
    }

    @GameTest(template = "empty_large")
    public static void sharedSectionsDoNotDependOnTheSizingResidentsPotion(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(6, 1, 6));
        for (BlockPos floor : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(2, -1, 2))) {
            level.setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
        }
        level.setBlock(at, Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(at.above(), Blocks.WATER.defaultBlockState(), 2);
        var ordinary = io.github.izakyl.folkways.plugins.person.PersonBody.RESIDENT.get().create(level);
        var protectedBody = io.github.izakyl.folkways.plugins.person.PersonBody.RESIDENT.get().create(level);
        ordinary.moveTo(Vec3.atBottomCenterOf(at.west()));
        protectedBody.moveTo(Vec3.atBottomCenterOf(at.west()));
        protectedBody.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.WATER_BREATHING, 600));
        var bounds = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(at.offset(-2, -1, -2),
            at.offset(2, 3, 2));
        try (var first = Grounds.watch(level, bounds); var second = Grounds.watch(level, bounds)) {
            boolean plain = first.now(List.of(ordinary)).standable(at);
            boolean protectedFirst = second.now(List.of(protectedBody)).standable(at);
            helper.assertTrue(plain == protectedFirst, "the same section is independent of the sizing body's effect");
            helper.assertTrue(first.now(List.of(protectedBody)).standable(at) == plain,
                "a cached section remains unchanged when the sizing body changes");
        }
        ordinary.discard();
        protectedBody.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void breathabilityUsesTheSearchRegionRatherThanTheMobsWorld(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos feet = helper.absolutePos(new BlockPos(2, 1, 2));
        var mob = EntityType.VILLAGER.create(level);
        mob.setPos(Vec3.atBottomCenterOf(feet));
        var region = Structures.region(level, feet.offset(-2, -2, -2), feet.offset(2, 3, 2));
        var flooded = new ObstructedPathRegion(level, feet, region,
            Map.of(feet.above(), Blocks.WATER.defaultBlockState()));
        helper.assertTrue(Breathing.canStay(mob, level, feet), "the actual world has air at head height");
        helper.assertFalse(Breathing.canStay(mob, flooded, feet), "the search sees its hypothetical water");
        level.setBlock(feet.above(), Blocks.WATER.defaultBlockState(), 2);
        var drained = new ObstructedPathRegion(level, feet, region,
            Map.of(feet.above(), Blocks.AIR.defaultBlockState()));
        helper.assertFalse(Breathing.canStay(mob, level, feet), "the actual world now has water");
        helper.assertTrue(Breathing.canStay(mob, drained, feet), "the search sees hypothetical air");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void taggedTopsAreNotWalkingFloors(GameTestHelper helper) {
        BlockPos floor = helper.absolutePos(new BlockPos(2, 1, 2));
        List<BlockState> forbidden = List.of(
            Blocks.BAMBOO.defaultBlockState(),
            Blocks.OAK_FENCE.defaultBlockState(),
            Blocks.COBBLESTONE_WALL.defaultBlockState(),
            Blocks.OAK_FENCE_GATE.defaultBlockState(),
            Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.OPEN, true),
            Blocks.IRON_BARS.defaultBlockState(),
            Blocks.GLASS_PANE.defaultBlockState(),
            Blocks.BLACK_STAINED_GLASS_PANE.defaultBlockState(),
            Blocks.CHAIN.defaultBlockState(),
            Blocks.CHAIN.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
            Blocks.END_ROD.defaultBlockState(),
            Blocks.END_ROD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST),
            Blocks.LIGHTNING_ROD.defaultBlockState(),
            Blocks.CANDLE.defaultBlockState(),
            Blocks.POINTED_DRIPSTONE.defaultBlockState());
        for (BlockState state : forbidden) {
            assertFloorType(helper, floor, state, PathType.OPEN);
        }
        List<BlockState> ordinary = List.of(
            Blocks.DIRT.defaultBlockState(),
            Blocks.FARMLAND.defaultBlockState(),
            Blocks.DIRT_PATH.defaultBlockState(),
            Blocks.SOUL_SAND.defaultBlockState(),
            Blocks.CHEST.defaultBlockState(),
            Blocks.STONE_SLAB.defaultBlockState(),
            Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP),
            Blocks.HOPPER.defaultBlockState(),
            Blocks.CAULDRON.defaultBlockState(),
            Blocks.COMPOSTER.defaultBlockState(),
            Blocks.SCAFFOLDING.defaultBlockState(),
            Blocks.LADDER.defaultBlockState());
        for (BlockState state : ordinary) {
            assertFloorType(helper, floor, state, PathType.WALKABLE);
        }
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (Half half : Half.values()) {
                for (StairsShape shape : StairsShape.values()) {
                    BlockState state = Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                        .setValue(BlockStateProperties.HALF, half)
                        .setValue(BlockStateProperties.STAIRS_SHAPE, shape);
                    assertFloorType(helper, floor, state, PathType.WALKABLE);
                }
            }
        }
        helper.getLevel().setBlock(floor.above(), Blocks.LADDER.defaultBlockState(), Block.UPDATE_CLIENTS);
        assertFloorType(helper, floor, Blocks.OAK_FENCE.defaultBlockState(), PathType.OPEN);
        assertFloorType(helper, floor, Blocks.BAMBOO.defaultBlockState(), PathType.OPEN);
        helper.succeed();
    }

    private static void assertFloorType(GameTestHelper helper, BlockPos floor, BlockState state, PathType expected) {
        var level = helper.getLevel();
        level.setBlock(floor, state, Block.UPDATE_CLIENTS);
        var mob = EntityType.VILLAGER.create(level);
        var context = new PathfindingContext(level, mob);
        var evaluator = new ResidentNodeEvaluator(floor.west());
        PathType type = evaluator.getPathType(context, floor.getX(), floor.getY() + 1, floor.getZ());
        helper.assertTrue(type == expected, "the cell above " + state + " is " + expected + ", got " + type);
        PathType vanilla = WalkNodeEvaluator.getPathTypeStatic(context, floor.mutable());
        if (Footing.forbidsStandingAbove(state) && vanilla != PathType.FENCE) {
            PathType inside = evaluator.getPathType(context, floor.getX(), floor.getY(), floor.getZ());
            helper.assertTrue(inside == vanilla, "a tagged block keeps its own path type: " + state);
        }
        if (state.is(Blocks.OAK_FENCE) || state.is(Blocks.COBBLESTONE_WALL)) {
            PathType inside = evaluator.getPathType(context, floor.getX(), floor.getY(), floor.getZ());
            helper.assertTrue(inside == PathType.FENCE,
                "fences and walls keep vanilla's path type");
        }
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
