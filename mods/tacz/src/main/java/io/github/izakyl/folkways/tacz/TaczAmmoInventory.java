package io.github.izakyl.folkways.tacz;

import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.init.ModItems;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** TACZ reloads see only finite loose rounds in the resident's actual pack. */
final class TaczAmmoInventory implements IItemHandler {
    private final ResidentEntity body;
    TaczAmmoInventory(ResidentEntity body) { this.body = body; }
    static boolean ordinary(ItemStack stack) { return !stack.isEmpty() && stack.is(ModItems.AMMO.get()) && stack.getItem() instanceof IAmmo; }
    @Override public int getSlots() { return body.pack().getContainerSize(); }
    @Override public ItemStack getStackInSlot(int slot) {
        ItemStack stack = body.pack().getItem(slot);
        return ordinary(stack) ? stack.copy() : ItemStack.EMPTY;
    }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
    @Override public ItemStack extractItem(int slot, int count, boolean simulate) {
        ItemStack stack = body.pack().getItem(slot);
        if (body.level().isClientSide() || !body.isAlive() || !body.hasWeaponSession() || !ordinary(stack) || count <= 0) { return ItemStack.EMPTY; }
        return simulate ? stack.copyWithCount(Math.min(count, stack.getCount())) : body.pack().removeItem(slot, count);
    }
    @Override public int getSlotLimit(int slot) { return body.pack().getMaxStackSize(); }
    @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
}
