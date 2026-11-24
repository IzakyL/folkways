package io.github.izakyl.folkways.core.shell.create.dispatch;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

// A read-only view; transfers keep using the native handler's extraction contract.
record ShelfInventory(IItemHandler handler) implements Container {

    public int getContainerSize() {
        return handler.getSlots();
    }

    public boolean isEmpty() {
        throw new UnsupportedOperationException();
    }

    public ItemStack getItem(int slot) {
        return handler.getStackInSlot(slot);
    }

    public ItemStack removeItem(int slot, int count) {
        throw new UnsupportedOperationException();
    }

    public ItemStack removeItemNoUpdate(int slot) {
        throw new UnsupportedOperationException();
    }

    public void setItem(int slot, ItemStack stack) {
        throw new UnsupportedOperationException();
    }

    public void setChanged() {
    }

    public boolean stillValid(Player player) {
        return false;
    }

    public void clearContent() {
        throw new UnsupportedOperationException();
    }
}
