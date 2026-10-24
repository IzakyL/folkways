package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

final class LayNode extends BuildNode {

    LayNode(BuildJob job, Ends ends) {
        super(job, ends);
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        BlockPos at = cell(level);
        BlockState goal = job().goal();
        Map<BlockPos, BlockState> before = remaining(level, job().before(), true);
        Map<BlockPos, BlockState> after = remaining(level, job().after(), false);
        if (!Bystanders.clear(level, after, stances(level))) {
            return refuse(BuildRefusal.OCCUPIED);
        }
        Set<BlockPos> shot = new LinkedHashSet<>();
        job().shot().forEach(cell -> shot.add(cell.block(level)));
        if (job().adjusts()) {
            Rubble.lay(level, who.body(), before, after, shot, at, data(level), ItemStack.EMPTY,
                SchematicPlacement.icon(job().cost()), false);
            return new Outcome.Done(List.of(WorkNoise.placed(at, goal)), List.of(), Optional.empty());
        }
        for (Map.Entry<BlockPos, BlockState> cell : before.entrySet()) {
            if (BuildJob.inTheWay(cell.getValue()) && cell.getValue().getDestroySpeed(level, cell.getKey()) < 0.0F) {
                return refuse(BuildRefusal.NO_WAY_TO_BUILD);
            }
        }
        List<ItemStack> drawn = spend(who);
        List<WorkNoise> heard = new ArrayList<>(2);
        before.forEach((cell, state) -> {
            if (BuildJob.inTheWay(state)) {
                heard.add(WorkNoise.broke(cell, state));
            }
        });
        ItemStack tool = BuildingPerks.fortunate(level, who,
            job().broken().map(state -> who.held(new ToolNeed.SuitableFor(state))).orElse(ItemStack.EMPTY));
        List<ItemStack> made = new ArrayList<>(Rubble.lay(level, who.body(), before, after, shot, at, data(level), tool,
            SchematicPlacement.icon(job().cost()), true));
        BuildingPerks.interrupt(who);
        heard.add(WorkNoise.placed(at, goal));
        for (ItemStack stack : drawn) {
            if (stack.getItem() instanceof BucketItem) {
                made.add(new ItemStack(Items.BUCKET));
            }
        }
        return new Outcome.Done(List.copyOf(heard), List.copyOf(made),
            Optional.of(new Xp(BuildContent.trade(), 1)));
    }

    private Map<BlockPos, BlockData> data(ServerLevel level) {
        Map<BlockPos, BlockData> addressed = new java.util.LinkedHashMap<>();
        job().data().forEach((pos, payload) -> addressed.put(pos.block(level), payload));
        return addressed;
    }

    // The cells the worker may stand in for this work, where the world keeps them.
    private List<BlockPos> stances(ServerLevel level) {
        List<BlockPos> cells = new ArrayList<>();
        if (job().footings() instanceof Stances.Cells(Set<WorldPos> footings)) {
            for (WorldPos footing : footings) {
                WorldSpaces.storage(level, footing).ifPresent(cells::add);
            }
        }
        return cells;
    }
}
