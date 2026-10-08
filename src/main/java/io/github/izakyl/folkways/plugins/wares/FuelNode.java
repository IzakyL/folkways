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
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

final class FuelNode extends CraftNode {

    private final ItemSpec fuel;
    private final Runnable lit;

    FuelNode(UUID id, WorldPos at, Block block, Stances stances, ItemSpec fuel, long count, Runnable lit) {
        super(specOf(id, at, stances, Workload.Once.of(Stations.LABOR_TICKS, WaresContent::haste))
                .needs(new Need(fuel, count))
                .doing(WaresContent.FUELING, fuel)
                .done(),
            at, block);
        this.fuel = fuel;
        this.lit = lit;
    }

    @Override
    public boolean ready(ServerLevel level) {
        Optional<Container> machine = machine(level);
        return standing(level) && machine.isPresent() && Machines.takes(machine.get(), Machines.FUEL_SLOT, fuel);
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        Optional<Container> machine = machine(level);
        if (machine.isEmpty()) {
            return Outcome.failed(WaresRefusal.NOT_A_STATION);
        }
        List<ItemStack> rejected = new ArrayList<>();
        int offered = 0;
        for (ItemStack drawn : who.supplied()) {
            offered += drawn.getCount();
            ItemStack left = Machines.insertIntoSlot(machine.get(), Machines.FUEL_SLOT, drawn);
            if (!left.isEmpty()) {
                rejected.add(left);
            }
        }
        if (offered > 0 && rejected.stream().mapToInt(ItemStack::getCount).sum() == offered) {
            return Outcome.failed(WaresRefusal.STATION_FULL);
        }
        lit.run();
        return new Outcome.Done(worked(), List.of(), earned(), rejected);
    }
}
