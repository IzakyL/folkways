package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** A crop's placement and harvest rules, registered by the plugin that knows its native lifecycle. */
public interface CropBehavior {
    Block block();

    // Preferred sowing goods first. Different items can plant the same crop without being interchangeable food.
    List<Item> seeds();

    boolean needsFarmland();

    default List<io.github.izakyl.folkways.core.api.work.Need> plantingMaterials(int plants) {
        return List.of();
    }

    default boolean sowsAt(BlockPos ground, int minX, int minZ, java.util.Set<BlockPos> plot) {
        return true;
    }

    default boolean plant(ServerLevel level, Worker who, BlockPos base, ItemStack seed, List<ItemStack> supplied) {
        return plant(level, who, base, seed);
    }

    // Native preparation that does not go through a hoe's usual tool action, such as a flooded rice paddy.
    // Planning and work ask the same question without changing the soil or the block above it.
    default Optional<BlockState> tilled(Level level, BlockPos ground, ItemStack hoe) {
        return Optional.empty();
    }

    default BlockState planted() {
        return block().defaultBlockState();
    }

    default boolean canPlant(LevelReader level, BlockPos base) {
        BlockState existing = level.getBlockState(base);
        return (existing.isAir() || existing.canBeReplaced()) && planted().canSurvive(level, base);
    }

    default List<BlockPos> placementCells(BlockPos base) {
        return List.of(base);
    }

    default boolean plant(ServerLevel level, Worker who, BlockPos base, ItemStack seed) {
        if (!(seed.getItem() instanceof BlockItem item) || !canPlant(level, base)) {
            return false;
        }
        List<BlockPos> cells = placementCells(base);
        if (cells.stream().anyMatch(cell -> !level.isLoaded(cell))) return false;
        List<BlockState> beforeStates = cells.stream().map(level::getBlockState).toList();
        BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(level, null, InteractionHand.MAIN_HAND,
            seed, new BlockHitResult(Vec3.atCenterOf(base.below()), Direction.UP, base.below(), false)));
        int before = seed.getCount();
        try {
            if (item.place(context).consumesAction() && seed.getCount() == before - 1
                    && level.getBlockState(base).is(block())) return true;
        } catch (RuntimeException | LinkageError broken) {
            restore(level, cells, beforeStates);
            seed.setCount(before);
            throw broken;
        }
        restore(level, cells, beforeStates);
        seed.setCount(before);
        return false;
    }

    private static void restore(ServerLevel level, List<BlockPos> cells, List<BlockState> states) {
        // Restore the whole original structure before notifying neighbors; a half-restored rice column collapses.
        for (int index = cells.size() - 1; index >= 0; index--) {
            level.setBlock(cells.get(index), states.get(index), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        for (int index = 0; index < cells.size(); index++) {
            level.updateNeighborsAt(cells.get(index), states.get(index).getBlock());
        }
    }

    boolean ripe(LevelReader level, BlockPos base);

    default List<BlockPos> harvest(LevelReader level, BlockPos base) {
        return ripe(level, base) ? List.of(base) : List.of();
    }

    // Other cells claimed by that root, even when only the root has drops.
    default List<BlockPos> attached(LevelReader level, List<BlockPos> roots) {
        return List.of();
    }

    default int reach() {
        return 1;
    }

    default List<Amount> leaves(int roots) {
        return Leaves.room(roots * 3);
    }

    default Optional<Harvest> gather(ServerLevel level, Worker who, List<BlockPos> roots) {
        return Optional.empty();
    }

    record Harvest(List<ItemStack> items, List<WorkNoise> noises, int worked) {
        public Harvest {
            items = items.stream().map(ItemStack::copy).toList();
            noises = List.copyOf(noises);
        }
    }
}
