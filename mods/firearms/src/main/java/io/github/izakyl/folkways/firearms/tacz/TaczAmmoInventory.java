package io.github.izakyl.folkways.firearms.tacz;

import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.init.ModItems;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** TACZ reloads see only finite loose rounds in the resident's actual pack, and unloads return rounds to it. */
final class TaczAmmoInventory implements IItemHandler {

    private final ResidentEntity body;

    TaczAmmoInventory(ResidentEntity body) {
        this.body = body;
    }

    static boolean ordinary(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.AMMO.get()) && stack.getItem() instanceof IAmmo;
    }

    @Override
    public int getSlots() {
        return body.pack().getContainerSize();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        ItemStack stack = body.pack().getItem(slot);
        return ordinary(stack) ? stack.copy() : ItemStack.EMPTY;
    }

    // Rounds a script unloads from the gun (TACZ 1.2.0+) go back into the pack; whatever does not fit TACZ drops.
    // The slot is ignored: slots holding other goods read as empty here, so the pack places the rounds itself.
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (!active() || !ordinary(stack)) {
            return stack;
        }
        if (!simulate) {
            return body.pack().insert(stack);
        }
        SimpleContainer trial = new SimpleContainer(body.pack().getContainerSize());
        for (int i = 0; i < trial.getContainerSize(); i++) {
            trial.setItem(i, body.pack().getItem(i).copy());
        }
        return Containers.insert(trial, stack);
    }

    @Override
    public ItemStack extractItem(int slot, int count, boolean simulate) {
        ItemStack stack = body.pack().getItem(slot);
        if (!active() || !ordinary(stack) || count <= 0) {
            return ItemStack.EMPTY;
        }
        return simulate ? stack.copyWithCount(Math.min(count, stack.getCount())) : body.pack().removeItem(slot, count);
    }

    private boolean active() {
        return !body.level().isClientSide() && body.isAlive() && body.weaponDrawn();
    }

    @Override
    public int getSlotLimit(int slot) {
        return body.pack().getMaxStackSize();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return false;
    }
}
