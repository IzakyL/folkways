package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

final class PackCraftNode extends CraftNode {

    private final int runs;
    private final Assembly assembly;

    PackCraftNode(WorldPos at, Block block, Stances stances, List<Ingredients.Demand> demands,
                  ItemSpec produces, long perRun, int runs, Assembly assembly) {
        super(specOf(at, stances,
                Workload.Once.of(runs * Stations.LABOR_TICKS, WaresContent::haste))
                .needs(needsFor(demands, runs))
                .gives(new Amount(produces, perRun * runs))
                .doing(WaresContent.CRAFTING, produces)
                .done(),
            at, block);
        this.runs = runs;
        this.assembly = assembly;
    }

    private static List<Need> needsFor(List<Ingredients.Demand> demands, int runs) {
        List<Need> needs = new ArrayList<>(demands.size());
        for (Ingredients.Demand demand : demands) {
            needs.add(new Need(demand.spec(), demand.count() * runs));
        }
        return List.copyOf(needs);
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        List<ItemStack> supplied = who.supplied();
        SimpleContainer pool = new SimpleContainer(Math.max(1, supplied.size()));
        supplied.forEach(stack -> Containers.insert(pool, stack));
        List<ItemStack> made = new ArrayList<>();
        for (int run = 0; run < runs; run++) {
            Optional<List<ItemStack>> ran = assembly.runOnce(level, pool);
            if (ran.isEmpty()) {
                return Outcome.failed(WaresRefusal.NOTHING_TO_WORK_WITH);
            }
            made.addAll(ran.get());
        }
        return new Outcome.Done(worked(), List.copyOf(made), earned(runs), pool.removeAllItems());
    }
}
