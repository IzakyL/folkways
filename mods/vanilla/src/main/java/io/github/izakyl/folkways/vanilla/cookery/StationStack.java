package io.github.izakyl.folkways.vanilla.cookery;

import net.minecraft.world.item.ItemStack;

public final class StationStack {
    private final ItemStack stack;

    public StationStack(ItemStack stack) {
        this.stack = stack.copy();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StationStack observed && ItemStack.matches(stack, observed.stack);
    }

    @Override
    public int hashCode() {
        return stack.isEmpty() ? 0 : 31 * ItemStack.hashItemAndComponents(stack) + stack.getCount();
    }
}
