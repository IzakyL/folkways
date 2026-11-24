package io.github.izakyl.folkways.core.shell.carts;

import com.jusipat.astikorcartsredux.entity.PlowEntity;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

record CartTools(PlowEntity plow) implements Port<ItemStack> {

    @Override
    public Optional<UUID> entityId() {
        return Optional.of(plow.getUUID());
    }

    @Override
    public WorldPos anchor() {
        return WorldSpaces.at(plow);
    }

    @Override
    public String part() {
        return "tools/" + plow.getUUID();
    }

    @Override
    public Object leaseKey() {
        return part();
    }

    @Override
    public boolean valid(ServerLevel level) {
        return plow.level() == level && level.getEntity(plow.getUUID()) == plow && plow.isAlive();
    }

    @Override
    public ResourceKind<ItemStack> resource() {
        return ResourceKind.ITEM;
    }

    @Override
    public List<ItemStack> contents(ServerLevel level) {
        return valid(level) ? plow.getItemStacks().stream().filter(stack -> !stack.isEmpty())
            .map(ItemStack::copy).toList() : List.of();
    }

    @Override
    public ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
        if (!valid(level)) {
            return ItemStack.EMPTY;
        }
        ItemStack left = Containers.insert(simulate ? snapshot() : plow, offered);
        return offered.copyWithCount(offered.getCount() - left.getCount());
    }

    @Override
    public ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
        if (!valid(level)) {
            return ItemStack.EMPTY;
        }
        return Containers.extract(simulate ? snapshot() : plow,
            stack -> ItemStack.isSameItemSameComponents(stack, wanted), wanted.getCount());
    }

    private SimpleContainer snapshot() {
        SimpleContainer trial = new SimpleContainer(plow.getContainerSize()) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return Containers.accepts(plow, slot, stack);
            }

            @Override
            public int getMaxStackSize() {
                return plow.getMaxStackSize();
            }
        };
        for (int slot = 0; slot < trial.getContainerSize(); slot++) {
            trial.setItem(slot, plow.getItem(slot).copy());
        }
        return trial;
    }
}
