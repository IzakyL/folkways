package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.inventory.api.StoreWorkstations;

import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import java.util.UUID;
import io.github.izakyl.folkways.core.work.api.WorkNoise;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

final class PackCraftNode extends CraftNode {

    private final int runs;
    private final Assembly assembly;
    private final StoreWorkstations.Capability capability;
    private final Predicate<ServerLevel> available;

    PackCraftNode(WorldPos at, Block block, Stances stances, List<Ingredients.Demand> demands,
                  ItemSpec produces, long perRun, int runs, Assembly assembly) {
        this(at, block, StoreWorkstations.vanilla(block), stances, level -> true, demands,
            produces, perRun, runs, assembly);
    }

    PackCraftNode(WorldPos at, Block block, StoreWorkstations.Capability capability, Stances stances,
                  Predicate<ServerLevel> available, List<Ingredients.Demand> demands,
                  ItemSpec produces, long perRun, int runs, Assembly assembly) {
        this(new WorkSite.AtBlock(at), block, capability, stances, available, demands,
            produces, perRun, runs, assembly);
    }

    PackCraftNode(WorkSite site, Block block, StoreWorkstations.Capability capability, Stances stances,
                  Predicate<ServerLevel> available, List<Ingredients.Demand> demands,
                  ItemSpec produces, long perRun, int runs, Assembly assembly) {
        super(specOf(site, stances,
                Workload.Once.of(runs * Stations.LABOR_TICKS,
                    who -> WaresContent.haste(who)
                        * Math.ceil(runs / who.effects().value(WaresContent.BATCH)) / runs))
                .needs(needsFor(demands, runs))
                .gives(new Amount(produces, perRun * runs))
                .leaves(assembly.remainders().stream().map(amount -> new Amount(amount.spec(), amount.count() * runs)).toList())
                .doing(WaresContent.CRAFTING, produces)
                .done(),
            site.where(), block);
        this.runs = runs;
        this.assembly = assembly;
        this.capability = capability;
        this.available = available;
    }

    @Override
    boolean standing(ServerLevel level) {
        return (spec().site() instanceof WorkSite.AtEntity || super.standing(level)) && available.test(level);
    }

    @Override
    public Optional<UUID> worker() {
        return spec().site() instanceof WorkSite.AtEntity entity ? Optional.of(entity.entity()) : Optional.empty();
    }

    @Override
    List<WorkNoise> worked() {
        return StationNoise.of(capability.station(), cell());
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
        // A draw may aggregate several unstackable buckets into one supplied amount. Restore their
        // actual native stack limits before crafting rather than losing the excess in a too-small pool.
        SimpleContainer limits = new SimpleContainer(0);
        int cells = Math.toIntExact(supplied.stream().mapToLong(stack -> {
            int size = Math.max(1, limits.getMaxStackSize(stack));
            return (stack.getCount() + (long) size - 1) / size;
        }).sum());
        SimpleContainer pool = new SimpleContainer(Math.max(1, cells));
        for (ItemStack stack : supplied) {
            if (!Containers.insert(pool, stack).isEmpty()) {
                return Outcome.failed(WaresRefusal.NOTHING_TO_WORK_WITH);
            }
        }
        List<ItemStack> made = new ArrayList<>();
        for (int run = 0; run < runs; run++) {
            Optional<List<ItemStack>> ran = assembly.runOnce(level, pool, who);
            if (ran.isEmpty()) {
                return Outcome.failed(WaresRefusal.NOTHING_TO_WORK_WITH);
            }
            made.addAll(ran.get());
        }
        return new Outcome.Done(worked(), List.copyOf(made), earned(runs), pool.removeAllItems());
    }
}
