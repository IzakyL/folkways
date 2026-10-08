package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

final class LoadNode extends CraftNode {

    private final ItemSpec input;
    private final int load;

    LoadNode(WorldPos at, Block block, Stances stances, ItemSpec input, int load, int cookTicks) {
        super(specOf(at, stances, Workload.Once.of(Stations.LABOR_TICKS, WaresContent::haste))
                .needs(new Need(input, load))
                .doing(WaresContent.LOADING, input)
                .estimate(Stations.LABOR_TICKS + cookTicks * load)
                .done(),
            at, block);
        this.input = input;
        this.load = load;
    }

    @Override
    public boolean ready(ServerLevel level) {
        Optional<Container> machine = machine(level);
        return standing(level) && machine.isPresent() && Machines.takes(machine.get(), Machines.INPUT_SLOT, input);
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        Optional<Container> machine = machine(level);
        if (machine.isEmpty()) {
            return Outcome.failed(WaresRefusal.NOT_A_STATION);
        }
        List<ItemStack> rejected = new ArrayList<>();
        for (ItemStack drawn : who.supplied()) {
            ItemStack left = Machines.insertIntoSlot(machine.get(), Machines.INPUT_SLOT, drawn);
            if (!left.isEmpty()) {
                rejected.add(left);
            }
        }
        if (rejected.stream().mapToInt(ItemStack::getCount).sum() == load) {
            return Outcome.failed(WaresRefusal.STATION_FULL);
        }
        return new Outcome.Done(worked(), List.of(), Optional.empty(), rejected);
    }

    @Override
    public boolean settled(ServerLevel level) {
        if (!level.isLoaded(cell())) {
            return false;
        }
        Optional<Container> machine = machine(level);
        return machine.isEmpty() || machine.get().getItem(Machines.INPUT_SLOT).isEmpty();
    }
}
