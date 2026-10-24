package io.github.izakyl.folkways.cookery.tools;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

// A worker may carry another knife too. Native actions must use the exact borrowed tool, including its components.
record LoanWorker(Worker delegate, TaskTools service,
                  Map<ItemSpec, KitchenTools.Lease> loans,
                  Optional<Vocation> vocation) implements Worker {

    LoanWorker(Worker delegate, TaskTools service,
            Map<ItemSpec, KitchenTools.Lease> loans) {
        this(delegate, service, loans, Optional.empty());
    }

    public Resident resident() { return delegate.resident(); }
    public Mob body() { return delegate.body(); }
    public Container pack() { return delegate.pack(); }
    public List<ItemStack> supplied() { return delegate.supplied(); }
    public List<Store> within() { return delegate.within(); }
    public void spill(ItemStack stack) { delegate.spill(stack); }
    public Effects effects() { return delegate.effects(); }
    public List<Node> route() { return delegate.route(); }
    public Placement placement() { return delegate.placement(); }

    public ItemStack held(ToolNeed need) {
        var family = Tools.shared(need);
        if (family.isEmpty()) {
            return delegate.held(need);
        }
        for (var lease : loans.values()) {
            if (!lease.resident().equals(resident().id()) || !Goods.overlap(lease.request().tools(), family.get())) {
                continue;
            }
            boolean active = service.pool.leases(service.colony.id()).stream().anyMatch(current ->
                current.id().equals(lease.id()) && current.phase() == KitchenTools.Phase.BORROWED);
            if (!active) {
                continue;
            }
            int slot = service.carriedSlot(pack(), lease, service.tool(lease).orElse(ItemStack.EMPTY));
            if (slot >= 0 && Tools.answers(pack().getItem(slot), need)
                    && KitRules.allows(
                        service.colony, vocation, pack().getItem(slot).getItem())) {
                return pack().getItem(slot);
            }
        }
        return ItemStack.EMPTY;
    }
}
