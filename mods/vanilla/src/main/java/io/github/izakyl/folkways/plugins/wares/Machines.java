package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

final class Machines {

    static final int INPUT_SLOT = 0;

    static final int FUEL_SLOT = 1;

    static final int RESULT_SLOT = 2;

    private Machines() {
    }

    static Optional<Container> at(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return Optional.empty();
        }
        return level.getBlockEntity(pos) instanceof Container container
            ? Optional.of(container)
            : Optional.empty();
    }

    static Optional<Container> finished(Level level, BlockPos pos) {
        Optional<Container> machine = at(level, pos);
        if (machine.isEmpty() || machine.get().getContainerSize() <= RESULT_SLOT
                || machine.get().getItem(RESULT_SLOT).isEmpty()) {
            return Optional.empty();
        }
        return machine;
    }

    static boolean coldWithCharge(Level level, BlockPos pos, BlockState state) {
        Optional<Container> machine = at(level, pos);
        if (machine.isEmpty() || machine.get().getItem(INPUT_SLOT).isEmpty()
                || !machine.get().getItem(FUEL_SLOT).isEmpty()) {
            return false;
        }
        return !(state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT));
    }

    // Whether the slot has room for some of `goods`: empty, or holding the same, short of a full stack.
    static boolean takes(Container machine, int slot, ItemSpec goods) {
        if (slot >= machine.getContainerSize()) {
            return false;
        }
        ItemStack there = machine.getItem(slot);
        return there.isEmpty()
            || Goods.matches(there, goods) && there.getCount() < Math.min(machine.getMaxStackSize(), there.getMaxStackSize());
    }

    static ItemStack takeFromSlot(Container from, int slot, int max, Predicate<ItemStack> which) {
        if (slot < 0 || slot >= from.getContainerSize() || max <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack there = from.getItem(slot);
        if (there.isEmpty() || !which.test(there)) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = from.removeItem(slot, Math.min(max, there.getCount()));
        if (!taken.isEmpty()) {
            from.setChanged();
        }
        return taken;
    }

    static ItemStack insertIntoSlot(Container into, int slot, ItemStack stack) {
        if (slot < 0 || slot >= into.getContainerSize() || stack.isEmpty()
            || !into.canPlaceItem(slot, stack)) {
            return stack;
        }
        int cap = Math.min(into.getMaxStackSize(), stack.getMaxStackSize());
        ItemStack there = into.getItem(slot);
        if (there.isEmpty()) {
            ItemStack left = stack.copy();
            into.setItem(slot, left.split(Math.min(cap, left.getCount())));
            into.setChanged();
            return left;
        }
        if (!ItemStack.isSameItemSameComponents(there, stack)) {
            return stack;
        }
        int moved = Math.min(cap - there.getCount(), stack.getCount());
        if (moved <= 0) {
            return stack;
        }
        there.grow(moved);
        into.setItem(slot, there);
        into.setChanged();
        ItemStack left = stack.copy();
        left.shrink(moved);
        return left;
    }
}
