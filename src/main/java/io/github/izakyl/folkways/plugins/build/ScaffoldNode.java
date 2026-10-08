package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

final class ScaffoldNode extends BuildNode {

    private final TemporaryScaffolds temporary;

    ScaffoldNode(BuildJob job, Ends ends, TemporaryScaffolds temporary) {
        super(job, ends);
        this.temporary = temporary;
    }

    static BlockState state() {
        return Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0);
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        List<WorldPos> column = List.copyOf(job().after().keySet());
        List<BlockPos> cells = new ArrayList<>(column.size());
        for (WorldPos cell : column) {
            cells.add(cell.block(level));
        }
        return job().kind() == BuildJob.Kind.RAISE ? raise(level, who, column, cells) : lower(level, who, column, cells);
    }

    private Outcome raise(ServerLevel level, Worker who, List<WorldPos> column, List<BlockPos> cells) {
        spend(who);
        for (BlockPos cell : cells) {
            level.setBlock(cell, state(), 3);
        }
        temporary.added(column);
        return Outcome.done();
    }

    // A worker counts as at a stance beside the column from as much as a block over it, so it may well stand on the
    // column's first block, or in its foot cell: it refused to lower the column for its own sake and was sent straight
    // back to the same spot, for good. It comes down no more than the block a step would, so it is no hindrance.
    private static boolean droppedSafely(LivingEntity body, Worker who, int foot) {
        return body == who.body() && body.getY() <= foot + 1.0D + 1.0E-3D;
    }

    private Outcome lower(ServerLevel level, Worker who, List<WorldPos> column, List<BlockPos> cells) {
        int foot = cells.stream().mapToInt(BlockPos::getY).min().orElse(0);
        for (int at = 0; at < cells.size(); at++) {
            if (!temporary.contains(column.get(at))) {
                return refuse(BuildRefusal.CONTESTED);
            }
            if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(cells.get(at).above()),
                    body -> !droppedSafely(body, who, foot)).isEmpty()) {
                return refuse(BuildRefusal.CONTESTED);
            }
        }
        for (int at = cells.size() - 1; at >= 0; at--) {
            level.setBlock(cells.get(at), Blocks.AIR.defaultBlockState(), 3);
        }
        temporary.removed(column);
        List<ItemStack> returned = new ArrayList<>();
        for (int left = cells.size(); left > 0; left -= Items.SCAFFOLDING.getDefaultMaxStackSize()) {
            returned.add(new ItemStack(Items.SCAFFOLDING, Math.min(left, Items.SCAFFOLDING.getDefaultMaxStackSize())));
        }
        return new Outcome.Done(List.of(), List.copyOf(returned), Optional.empty());
    }
}
