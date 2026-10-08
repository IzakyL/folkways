package io.github.izakyl.folkways.front.ui.menu;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.ui.FilterStacks;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class FilterSlot extends Slot {
    private final FilterInventory filters;
    private BooleanSupplier active = () -> true;

    public FilterSlot(FilterInventory filters, int index, int x, int y) {
        super(filters, index, x, y);
        this.filters = filters;
    }

    public FilterSlot activeWhen(BooleanSupplier active) {
        this.active = active;
        return this;
    }

    @Override
    public boolean isActive() {
        return active.getAsBoolean();
    }

    public FilterInventory filters() {
        return filters;
    }

    public Optional<ItemFilter> filter() {
        return FilterStacks.parse(getItem());
    }

    public boolean canSetFilterTo(ItemStack stack) {
        return stack.isEmpty() || filters.canPlaceItem(getContainerSlot(), stack);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    @Override
    public boolean mayPickup(Player player) {
        return false;
    }

    @Override
    public void onTake(Player player, ItemStack stack) {
    }

    @Override
    public ItemStack remove(int amount) {
        return ItemStack.EMPTY;
    }

    @Override
    public void set(ItemStack stack) {
        if (!canSetFilterTo(stack)) {
            return;
        }
        super.set(clamp(stack));
    }

    public void increase(ItemStack hand) {
        set(hand.isEmpty() ? ItemStack.EMPTY : hand.copy());
    }

    public void decrease(ItemStack hand) {
        set(getItem().isEmpty() ? hand.copy() : ItemStack.EMPTY);
    }

    private static ItemStack clamp(ItemStack stack) {
        return stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
    }
}
