package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Recourse;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class ItemDeposit implements Node {

    private final Rig.Part part;
    private final Need need;
    private final NodeSpec spec;
    private int retries;

    ItemDeposit(ResourceLocation owner, Rig.Part part, Need need) {
        this.part = part;
        this.need = need;
        spec = NodeSpec.of(UUID.randomUUID(), owner, part.site(), part.stances(), Workload.Once.of(0))
            .needs(need).done();
    }

    Rig.Part part() {
        return part;
    }

    PostTransfer transfer() {
        return PostTransfer.deposit(part.ref(), need);
    }

    static Optional<Port<ItemStack>> port(ServerLevel level, Rig.Part part) {
        return Ports.at(level, part.ref().at(),
            ResourceKind.ITEM, part.ref().part())
            .filter(port -> port.valid(level));
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker worker) {
        Optional<Port<ItemStack>> port = port(level, part);
        if (port.isEmpty()) {
            return Outcome.failed(FacilityRefusal.GONE);
        }
        Optional<Lease> lease = Lease.take(port.get(), worker);
        if (lease.isEmpty()) {
            return Outcome.failed(FacilityRefusal.BUSY);
        }
        try (Lease ignored = lease.get()) {
            List<ItemStack> remaining = new ArrayList<>();
            List<ItemStack> offered = new ArrayList<>();
            long left = need.count();
            for (ItemStack stack : worker.supplied()) {
                ItemStack keep = stack.copy();
                if (left > 0 && Goods.matches(stack, need.spec())) {
                    int take = (int) Math.min(left, stack.getCount());
                    offered.add(keep.split(take));
                    left -= take;
                }
                if (!keep.isEmpty()) {
                    remaining.add(keep);
                }
            }
            if (left > 0) {
                return Outcome.failed(FacilityRefusal.REFUSED);
            }
            for (ItemStack stack : offered) {
                port.get().prepareDeposit(level, worker.body(), stack);
                if (port.get().deposit(level, worker.body(), stack, true).getCount() != stack.getCount()) {
                    return Outcome.failed(FacilityRefusal.BUSY);
                }
            }
            long delivered = 0;
            for (ItemStack stack : offered) {
                int moved = port.get().deposit(level, worker.body(), stack, false).getCount();
                delivered += moved;
                if (moved < stack.getCount()) {
                    remaining.add(stack.copyWithCount(stack.getCount() - moved));
                }
            }
            if (delivered == 0) {
                return Outcome.failed(FacilityRefusal.BUSY);
            }
            return new Outcome.Done(List.of(), List.of(), Optional.empty(), remaining);
        }
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        return why == FacilityRefusal.BUSY && retries++ < 10 ? Recourse.waitFor(10) : Recourse.FAIL;
    }
}
