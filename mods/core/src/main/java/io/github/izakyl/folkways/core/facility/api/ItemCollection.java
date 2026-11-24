package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class ItemCollection implements Node {

    private final Rig.Part part;
    private final List<Amount> wanted;
    private final boolean all;
    private final NodeSpec spec;

    ItemCollection(Rig.Part part, List<Amount> wanted, boolean all) {
        this.part = part;
        this.wanted = wanted;
        this.all = all;
        spec = NodeSpec.of(UUID.randomUUID(), DefinedFacilities.ID, part.site(), part.stances(), Workload.Once.of(0))
            .leaves(wanted).done();
    }

    PostTransfer transfer() {
        return new PostTransfer(part.ref(), null, wanted, all);
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker worker) {
        var port = ItemDeposit.port(level, part);
        if (port.isEmpty()) {
            return Outcome.failed(FacilityRefusal.GONE);
        }
        var lease = Lease.take(port.get(), worker);
        if (lease.isEmpty()) {
            return Outcome.failed(FacilityRefusal.BUSY);
        }
        try (Lease ignored = lease.get()) {
            var available = port.get().contents(level).stream().map(ItemStack::copy).toList();
            List<ItemStack> selected = new ArrayList<>();
            if (all) {
                selected.addAll(available);
            }
            for (Amount amount : wanted) {
                long left = amount.count();
                for (ItemStack stack : available) {
                    if (!stack.isEmpty() && Goods.matches(stack, amount.spec())) {
                        int count = (int) Math.min(left, stack.getCount());
                        if (count > 0) {
                            selected.add(stack.copyWithCount(count));
                            stack.shrink(count);
                            left -= count;
                        }
                    }
                }
                if (left != 0) {
                    return Outcome.failed(FacilityRefusal.REFUSED);
                }
            }
            if (selected.stream().anyMatch(stack ->
                    port.get().draw(level, worker.body(), stack, true).getCount() != stack.getCount())) {
                return Outcome.failed(FacilityRefusal.REFUSED);
            }
            List<ItemStack> made = selected.stream().map(stack -> port.get().draw(level, worker.body(), stack, false))
                .filter(stack -> !stack.isEmpty()).toList();
            return new Outcome.Done(List.of(), made, Optional.empty());
        }
    }
}
