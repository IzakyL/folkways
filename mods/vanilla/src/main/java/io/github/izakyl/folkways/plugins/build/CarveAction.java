package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.WorkNoise;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Xp;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class CarveAction extends BuildAction {
    CarveAction(BuildJob job) {
        super(job);
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        Map<BlockPos, BlockState> after = remaining(level, job().after(), false);
        List<BlockPos> stances = new ArrayList<>();
        if (job().footings() instanceof Stances.Cells(Set<WorldPos> feet)) {
            feet.forEach(foot -> WorldSpaces.storage(level, foot).ifPresent(stances::add));
        }
        Map<BlockPos, BlockState> occupied = new LinkedHashMap<>();
        after.forEach((pos, state) -> occupied.put(pos, StructureCompat.of(state).isPresent()
            ? Blocks.STONE.defaultBlockState() : state));
        if (!Bystanders.clear(level, occupied, stances)) {
            return refuse(BuildRefusal.OCCUPIED);
        }
        Carving.Quote quote;
        List<ItemStack> unused = new ArrayList<>(who.supplied());
        Map<BlockPos, BlockEntity> prepared = new LinkedHashMap<>();
        Map<BlockPos, BlockState> rubble = new LinkedHashMap<>();
        try (var processing = StructureCompat.processing()) {
            quote = Carving.quote(level, after, pos -> data(level, pos), cache);
            for (var need : quote.needs()) {
                long left = need.count();
                for (ItemStack stack : unused) {
                    if (Goods.matches(stack, need.spec())) {
                        int used = (int) Math.min(left, stack.getCount());
                        stack.shrink(used);
                        left -= used;
                    }
                }
                if (left > 0) {
                    return refuse(BuildRefusal.MATERIALS_CHANGED);
                }
            }
            for (var cell : after.entrySet()) {
                BlockPos pos = cell.getKey();
                BlockState standing = level.getBlockState(pos);
                if (BuildJob.inTheWay(standing) && standing.getDestroySpeed(level, pos) < 0) {
                    return refuse(BuildRefusal.NO_WAY_TO_BUILD);
                }
                var handler = StructureCompat.of(cell.getValue()).or(() -> StructureCompat.of(standing)).or(StructureCompat::carver);
                if (BuildJob.inTheWay(standing) && StructureCompat.of(standing).isEmpty()
                    && handler.map(found -> found.solid(standing).isEmpty()).orElse(true)) {
                    rubble.put(pos, standing);
                }
                if (StructureCompat.of(cell.getValue()).isPresent()) {
                    prepared.put(pos, data(level, pos).entity(level, pos, cell.getValue()));
                }
            }
        } catch (RuntimeException invalid) {
            return refuse(BuildRefusal.NO_WAY_TO_BUILD);
        }
        ItemStack tool = BuildingPerks.fortunate(level, who, job().broken()
            .map(state -> who.held(new ToolNeed.SuitableFor(state)))
            .orElse(ItemStack.EMPTY));
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        Map<BlockPos, BlockEntity> entities = new LinkedHashMap<>();
        List<ItemStack> made = new ArrayList<>();
        for (BlockPos pos : after.keySet()) {
            before.put(pos, level.getBlockState(pos));
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity != null) {
                entities.put(pos, entity);
            }
            if (level.isOutsideBuildHeight(pos)) {
                return refuse(BuildRefusal.NO_WAY_TO_BUILD);
            }
        }
        rubble.forEach((pos, state) -> {
            made.addAll(Block.getDrops(state, level, pos, level.getBlockEntity(pos), who.body(), tool));
            made.addAll(Joints.joiner().loosen(level, pos));
        });
        try {
            for (var cell : after.entrySet()) {
                BlockPos pos = cell.getKey();
                BlockState goal = cell.getValue();
                if (prepared.containsKey(pos)) {
                    level.removeBlockEntity(pos);
                }
                level.setBlock(pos, goal, Rubble.SHOT);
                if (!level.getBlockState(pos).equals(goal)) {
                    throw new IllegalStateException("Carving placement failed");
                }
                BlockEntity entity = prepared.get(pos);
                if (entity != null) {
                    level.setBlockEntity(entity);
                    if (level.getBlockEntity(pos) != entity) {
                        throw new IllegalStateException("Carving entity placement failed");
                    }
                    entity.setChanged();
                    level.sendBlockUpdated(pos, goal, goal, Block.UPDATE_CLIENTS);
                }
            }
        } catch (RuntimeException failed) {
            before.forEach((pos, state) -> {
                level.removeBlockEntity(pos);
                level.setBlock(pos, state, Rubble.SHOT);
                BlockEntity entity = entities.get(pos);
                if (entity != null) {
                    entity.clearRemoved();
                    level.setBlockEntity(entity);
                    entity.setChanged();
                }
            });
            return refuse(BuildRefusal.NO_WAY_TO_BUILD);
        }
        rubble.forEach((pos, state) -> state.spawnAfterBreak(level, pos, tool, true));
        // Commits run on the server thread: no other worker can spend between this quote and this write.
        cache.accept(quote);
        BuildingPerks.interrupt(who);
        return new Outcome.Done(List.of(WorkNoise.placed(cell(level), job().goal())), made,
            Optional.of(new Xp(BuildContent.trade(), 1)), unused.stream().filter(stack -> !stack.isEmpty()).toList());
    }

    private BlockData data(ServerLevel level, BlockPos pos) {
        return job().data().entrySet().stream().filter(entry -> entry.getKey().block(level).equals(pos))
            .map(Map.Entry::getValue).findFirst().orElse(BlockData.EMPTY);
    }
}
