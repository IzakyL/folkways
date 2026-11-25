package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.Readiness;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

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
    public Readiness readiness(ServerLevel level) {
        if (!level.isLoaded(cell())) {
            return Readiness.notYet();
        }
        if (!standing(level)) {
            return Readiness.READY;
        }
        Optional<Container> machine = machine(level);
        if (machine.isEmpty() || Goods.matches(machine.get().getItem(Machines.RESULT_SLOT), produces)) {
            return Readiness.READY;
        }
        var state = level.getBlockState(cell());
        if (machine.get() instanceof FurnaceTraining furnace && state.getOptionalValue(BlockStateProperties.LIT)
                .orElse(false) && machine.get().getItem(Machines.RESULT_SLOT).isEmpty()) {
            long ticks = furnace.folkways$cookingTicks();
            if (ticks > 0) {
                return Readiness.notYet(ticks);
            }
        }
        return Readiness.notYet();
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
