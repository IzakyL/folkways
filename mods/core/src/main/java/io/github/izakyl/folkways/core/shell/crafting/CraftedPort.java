package io.github.izakyl.folkways.core.shell.crafting;

import net.minecraft.network.chat.Component;
import io.github.izakyl.folkways.core.facility.api.control.TransformHint;
import io.github.izakyl.folkways.core.facility.api.control.SlotWindow;
import io.github.izakyl.folkways.core.facility.api.control.PortIdentity;
import io.github.izakyl.folkways.core.facility.api.control.PortReader;
import io.github.izakyl.folkways.core.facility.api.control.Pull;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.TransferResult;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** A crafting delivery may be consumed by a waiting native task instead of increasing ordinary stock. */
public record CraftedPort(ItemPort delegate, Insert insert) implements ItemPort {

    @FunctionalInterface
    public interface Insert {
        long accept(ItemStack item, long count, boolean simulate);
    }

    @Override
    public WorldPos anchor() {
        return identity().home();
    }

    @Override
    public PortIdentity identity() {
        return delegate.identity();
    }

    @Override
    public Optional<Component> unavailable(ServerLevel level) {
        return delegate.unavailable(level);
    }

    @Override
    public List<TransformHint> transforms() {
        return delegate.transforms();
    }

    @Override
    public Set<Operation> operations() {
        return delegate.operations();
    }

    @Override
    public PortReader reader(ServerLevel level, Optional<SlotWindow> window) {
        return delegate.reader(level, window);
    }

    @Override
    public TransferResult take(ServerLevel level, List<Pull> requested, Optional<SlotWindow> window, boolean simulate) {
        return delegate.take(level, requested, window, simulate);
    }

    @Override
    public TransferResult put(ServerLevel level, List<ItemStack> goods, Optional<SlotWindow> window, boolean simulate) {
        if (!valid(level) || !operations().contains(Operation.PUT)
                || window.isPresent() || goods.isEmpty()) {
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
