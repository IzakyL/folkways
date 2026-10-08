package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

final class TakeCookedNode extends CraftNode {

    private final ItemSpec produces;
    private final long count;
    private final Runnable out;

    TakeCookedNode(UUID id, WorldPos at, Block block, Stances stances, ItemSpec produces, long count, Runnable out) {
        super(specOf(id, at, stances, Workload.Once.of(Stations.LABOR_TICKS, WaresContent::haste))
                .gives(new Amount(produces, count))
                .doing(WaresContent.UNLOADING, produces)
                .done(),
            at, block);
        this.produces = produces;
        this.count = count;
        this.out = out;
    }

    @Override
    public boolean ready(ServerLevel level) {
        if (!level.isLoaded(cell())) {
            return false;
        }
        if (!standing(level)) {
            return true;
        }
        Optional<Container> machine = machine(level);
        return machine.isEmpty()
            || Goods.matches(machine.get().getItem(Machines.RESULT_SLOT), produces);
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        Optional<Container> machine = machine(level);
        if (machine.isEmpty()) {
            return Outcome.failed(WaresRefusal.NOT_A_STATION);
        }
        ItemStack taken = Machines.takeFromSlot(machine.get(), Machines.RESULT_SLOT,
            (int) Math.min(count, Integer.MAX_VALUE), stack -> Goods.matches(stack, produces));
        if (taken.isEmpty()) {
            return Outcome.failed(WaresRefusal.NOTHING_TO_WORK_WITH);
        }
        out.run();
        return new Outcome.Done(worked(), List.of(taken), earned());
    }
}
