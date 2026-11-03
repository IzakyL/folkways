package io.github.izakyl.folkways.plugins.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;

/**
 * Writes a piece of work into the world as the schematicannon writes a shot: no shape is worked out again and no
 * neighbour is told. What the work breaks is gathered first, since the colony keeps it.
 */
final class Rubble {

    // The cannon's own flags: the block goes to the clients, its neighbours keep their shapes.
    static final int SHOT = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private Rubble() {
    }

    /**
     * Fires the shot at {@code anchor}: what stands in the way is broken (its drops returned when {@code breaking}),
     * any part of it outside the shot is cleared, and the shot is placed. A fluid is poured instead, and flows.
     */
    static List<ItemStack> lay(ServerLevel level, @Nullable LivingEntity body, Map<BlockPos, BlockState> before,
            Map<BlockPos, BlockState> after, Set<BlockPos> shot, BlockPos anchor, Map<BlockPos, BlockData> data,
            ItemStack tool, ItemStack icon, boolean breaking) {
        List<ItemStack> drops = breaking ? broken(level, body, before, tool) : new ArrayList<>();
        after.forEach((cell, state) -> {
            if (!shot.contains(cell)) {
                level.setBlock(cell, state, SHOT);
            }
        });
        BlockState aimed = after.get(anchor);
        if (aimed != null && shot.contains(anchor)) {
            if (aimed.getBlock() instanceof LiquidBlock) {
                level.setBlock(anchor, aimed, Block.UPDATE_ALL);
            } else {
                SchematicPlacement.Launch launch = new SchematicPlacement.Launch(anchor, shot, List.of(anchor));
                SchematicPlacement.adapter().fire(level, launch,
                    cell -> after.getOrDefault(cell, level.getBlockState(cell)),
                    cell -> data.getOrDefault(cell, BlockData.EMPTY), icon);
                for (BlockPos cell : shot) {
                    soakWhereWater(level, cell, before.get(cell), after.get(cell));
                    BlockEntity entity = level.getBlockEntity(cell);
                    if (entity != null) {
                        // Not physics, only bookkeeping: the chunk saves the data and the clients see it.
                        entity.setChanged();
                        level.sendBlockUpdated(cell, entity.getBlockState(), entity.getBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        if (breaking) {
            before.forEach((cell, state) -> {
                if (BuildJob.inTheWay(state)) {
                    state.spawnAfterBreak(level, cell, tool, true);
                }
            });
        }
        return drops;
    }

    /**
     * Where water stood, a block the target wants waterlogged takes it in as it is laid, as one placed by hand would:
     * the cannon lays it dry, and only the block is spent. Where none stood, the water is let in by a step of its own.
     */
    static void soakWhereWater(ServerLevel level, BlockPos cell, @Nullable BlockState was, @Nullable BlockState wanted) {
        BlockState laid = level.getBlockState(cell);
        if (wanted != null && Step.soaked(wanted) && was != null && was.getFluidState().getType() == Fluids.WATER
                && laid.is(wanted.getBlock()) && laid.hasProperty(BlockStateProperties.WATERLOGGED)) {
            level.setBlock(cell, laid.setValue(BlockStateProperties.WATERLOGGED, true), SHOT);
        }
    }

    /** Clears blocks the target wants gone, as the cannon prints air: their drops are kept. */
    static List<ItemStack> clear(ServerLevel level, LivingEntity body, Map<BlockPos, BlockState> before,
            Map<BlockPos, BlockState> after, ItemStack tool) {
        List<ItemStack> drops = new ArrayList<>();
        Map<BlockPos, BlockState> broken = new LinkedHashMap<>();
        before.forEach((cell, state) -> {
            if (!state.isAir() && !state.liquid()) {
                drops.addAll(Block.getDrops(state, level, cell, level.getBlockEntity(cell), body, tool));
                drops.addAll(Joints.joiner().loosen(level, cell));
                broken.put(cell, state);
            }
        });
        after.forEach((cell, state) -> level.setBlock(cell, state, SHOT));
        broken.forEach((cell, state) -> state.spawnAfterBreak(level, cell, tool, true));
        return drops;
    }

    private static List<ItemStack> broken(ServerLevel level, @Nullable LivingEntity body,
            Map<BlockPos, BlockState> before, ItemStack tool) {
        List<ItemStack> drops = new ArrayList<>();
        before.forEach((cell, state) -> {
            if (BuildJob.inTheWay(state)) {
                drops.addAll(Block.getDrops(state, level, cell, level.getBlockEntity(cell), body, tool));
                drops.addAll(Joints.joiner().loosen(level, cell));
            }
        });
        return drops;
    }
}
