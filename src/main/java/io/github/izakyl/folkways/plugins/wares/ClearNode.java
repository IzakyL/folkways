package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

final class ClearNode extends CraftNode {

    // What is in the result slot no work booked at the cooker is waiting for: only that is cleared.
    private final Predicate<ItemStack> stray;

    ClearNode(UUID id, WorldPos at, Block block, Stances stances, Predicate<ItemStack> stray) {
        super(specOf(id, at, stances, Workload.Once.of(Stations.LABOR_TICKS, WaresContent::haste))
                .doing(WaresContent.CLEARING_STATION, block)
                .done(),
            at, block);
        this.stray = stray;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return standing(level) && Machines.finished(level, cell())
            .filter(machine -> stray.test(machine.getItem(Machines.RESULT_SLOT))).isPresent();
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        Optional<Container> machine = Machines.finished(level, cell());
        if (machine.isEmpty() || !stray.test(machine.get().getItem(Machines.RESULT_SLOT))) {
            return Outcome.failed(WaresRefusal.NOTHING_TO_WORK_WITH);
        }
        ItemStack taken = Machines.takeFromSlot(machine.get(), Machines.RESULT_SLOT,
            machine.get().getItem(Machines.RESULT_SLOT).getCount(), stack -> !stack.isEmpty());
        if (taken.isEmpty()) {
            return Outcome.failed(WaresRefusal.NOTHING_TO_WORK_WITH);
        }
        return new Outcome.Done(worked(), List.of(taken), earned());
    }
}
