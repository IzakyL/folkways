package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.PortReader;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.TransferResult;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** A crafting delivery may be consumed by a waiting native task instead of increasing ordinary stock. */
public record CraftedPort(StorePort delegate, Insert insert) implements StorePort {

    @FunctionalInterface
    public interface Insert {
        long accept(ItemStack item, long count, boolean simulate);
    }

    @Override
    public PortIdentity identity() {
        return delegate.identity();
    }

    @Override
    public Usability usability() {
        return delegate.usability();
    }

    @Override
    public Set<Operation> operations() {
        return delegate.operations();
    }

    @Override
    public PortReader reader(ServerLevel level, List<StoreRule> rules) {
        return delegate.reader(level, rules);
    }

    @Override
    public TransferResult take(ServerLevel level, List<Pull> requested, List<StoreRule> rules, boolean simulate) {
        return delegate.take(level, requested, rules, simulate);
    }

    @Override
    public TransferResult put(ServerLevel level, List<ItemStack> goods, List<StoreRule> rules,
            boolean directed, boolean simulate) {
        if (!usability().usable() || !operations().contains(Operation.PUT)
                || !StoreRule.stores(rules) || rules.stream().anyMatch(StoreRule.Slots.class::isInstance)
                || goods.isEmpty() || goods.stream().anyMatch(stack -> !StoreRule.admits(rules, stack))) {
            return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
        }
        ItemStack item = goods.getFirst();
        if (goods.stream().anyMatch(stack -> !ItemStack.isSameItemSameComponents(stack, item))) {
            return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
        }
        long count = goods.stream().mapToLong(ItemStack::getCount).sum();
        if (insert.accept(item, count, true) != count) {
            return new TransferResult.Refused(TransferResult.Why.NO_ROOM);
        }
        if (!simulate && insert.accept(item, count, false) != count) {
            throw new IllegalStateException("native crafting insertion disagreed with its simulation");
        }
        return new TransferResult.Done(goods);
    }
}
