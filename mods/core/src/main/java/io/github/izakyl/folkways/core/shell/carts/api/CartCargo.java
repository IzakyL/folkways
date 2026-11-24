package io.github.izakyl.folkways.core.shell.carts.api;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.List;
import java.util.function.Predicate;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/** The cart's own inventory, with a stable lease key while the cart moves. */
public record CartCargo(AbstractCargoCart cart) implements Port<ItemStack> {

    @Override
    public Optional<UUID> entityId() {
        return Optional.of(cart.getUUID());
    }

    @Override
    public WorldPos anchor() {
        return WorldSpaces.at(cart);
    }

    @Override
    public String part() {
        return "cargo/" + cart.getUUID();
    }

    @Override
    public Object leaseKey() {
        return part();
    }

    @Override
    public boolean valid(ServerLevel level) {
        return cart.level() == level && level.getEntity(cart.getUUID()) == cart && cart.isAlive();
    }

    @Override
    public ResourceKind<ItemStack> resource() {
        return ResourceKind.ITEM;
    }

    @Override
    public List<ItemStack> contents(ServerLevel level) {
        return valid(level) ? cart.getItemStacks().stream().filter(stack -> !stack.isEmpty())
            .map(ItemStack::copy).toList() : List.of();
    }

    @Override
    public Cost cost() {
        return Cost.REMOTE;
    }

    @Override
    public ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
        if (!valid(level)) {
            return ItemStack.EMPTY;
        }
        ItemStack left = Containers.insert(simulate ? snapshot() : cart, offered);
        return offered.copyWithCount(offered.getCount() - left.getCount());
    }

    /** Checks an entire consignment against the same simulated buffer before committing any part. */
    public boolean load(ServerLevel level, Mob writer, List<ItemStack> goods) {
        if (!valid(level)) {
            return false;
        }
        SimpleContainer trial = snapshot();
        if (goods.stream().anyMatch(stack -> !Containers.insert(trial, stack).isEmpty())) {
            return false;
        }
        goods.forEach(stack -> deposit(level, writer, stack, false));
        return true;
    }

    @Override
    public ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
        if (!valid(level)) {
            return ItemStack.EMPTY;
        }
        return Containers.extract(simulate ? snapshot() : cart,
            stack -> ItemStack.isSameItemSameComponents(stack, wanted), wanted.getCount());
    }

    public ItemStack drawMatching(ServerLevel level, Predicate<ItemStack> wanted, int limit) {
        return valid(level) ? Containers.extract(cart, wanted, limit) : ItemStack.EMPTY;
    }

    private SimpleContainer snapshot() {
        SimpleContainer trial = new SimpleContainer(cart.getContainerSize()) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return Containers.accepts(cart, slot, stack);
            }

            @Override
            public int getMaxStackSize() {
                return cart.getMaxStackSize();
            }
        };
        for (int slot = 0; slot < trial.getContainerSize(); slot++) {
            trial.setItem(slot, cart.getItem(slot).copy());
        }
        return trial;
    }
}
