package io.github.izakyl.folkways.plugins.supply;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

record SupplyWorkshop(UUID key, Colony colony, WorldPos at, Stances stances, SupplyBox source,
                      long revision) implements Workshop {
    public ResourceLocation id() { return SupplyContent.ID; }
    public WorkSite site() { return new WorkSite.AtBlock(at); }

    public List<Refinement.Change> options(Intent intent, Grown graph) {
        if (!(intent instanceof Produce produce)) return List.of();
        // Only inspect the requested alternatives. An all-items box never materializes all registry items.
        return Goods.members(produce.wanted()).stream().filter(source::allows)
            .sorted(Comparator.comparing(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString()))
            .map(item -> {
                int count = (int) produce.runs(site(), List.of(), 1, Optional.empty(), item.getDefaultInstance().getMaxStackSize());
                return Refinement.Change.of(Grown.of(new Withdrawal(new ItemStack(item, count))));
            }).toList();
    }

    private final class Withdrawal implements Node {
        private final ItemStack goods;
        private final NodeSpec spec;
        private boolean used;

        Withdrawal(ItemStack goods) {
            this.goods = goods;
            spec = NodeSpec.of(UUID.randomUUID(), SupplyContent.ID, site(), stances, Workload.Once.of(10))
                .gives(new Amount(Goods.specOf(goods), goods.getCount()))
                .leaves(List.of(new Amount(Goods.specOf(goods), goods.getCount())))
                .doing(SupplyContent.TAKING, Goods.specOf(goods)).done();
        }

        public NodeSpec spec() { return spec; }

        public List<Amount> planningLeaves(List<Amount> promised) {
            return Leaves.unpromised(spec.leaves(), promised);
        }

        private boolean valid(ServerLevel level) {
            return !used && at.in(level) && level.isLoaded(at.block(level)) && !source.isRemoved()
                && level.getBlockEntity(at.block(level)) == source && source.revision() == revision
                && source.allows(goods.getItem()) && colony.holdingAt(at)
                    .filter(holding -> holding.owner().equals(SupplyContent.SOURCE)).isPresent();
        }

        public Optional<io.github.izakyl.folkways.core.api.terms.RefusalKind> refusal(ServerLevel level) {
            return used || valid(level) ? Optional.empty() : Optional.of(SupplyRefusal.SOURCE_CHANGED);
        }

        public Outcome commit(ServerLevel level, Worker who) {
            if (!valid(level)) return Outcome.failed(SupplyRefusal.SOURCE_CHANGED);
            if (who.body().level() != level || !Reach.standingIn(who.body(), stances)) {
                return Outcome.failed(SupplyRefusal.OUT_OF_REACH);
            }
            if (room(who.pack(), goods) < goods.getCount()) return Outcome.failed(SupplyRefusal.NO_ROOM);
            used = true;
            source.generated(goods);
            return new Outcome.Done(List.of(), List.of(goods.copy()), Optional.empty());
        }
    }

    static long room(Container pack, ItemStack goods) {
        long free = 0;
        int cap = Math.min(goods.getMaxStackSize(), pack.getMaxStackSize());
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            if (!Containers.accepts(pack, slot, goods)) continue;
            ItemStack there = pack.getItem(slot);
            if (there.isEmpty()) free += cap;
            else if (ItemStack.isSameItemSameComponents(there, goods)) free += Math.max(0, cap - there.getCount());
        }
        return free;
    }
}
