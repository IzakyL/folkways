package io.github.izakyl.folkways.plugins.cookery.oil;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.decoration.OilPotBlockEntity;
import io.github.izakyl.folkways.core.api.terms.Containers;
import java.util.Optional;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** A view of the one actual native 256-oil slot, with no copied inventory or extra logical slots. */
public final class PlacedOilStore implements Container {
    private static boolean registered;
    private final OilPotBlockEntity pot;
    private final IItemHandlerModifiable nativeSlot;

    private PlacedOilStore(OilPotBlockEntity pot, IItemHandlerModifiable nativeSlot) {
        this.pot = pot; this.nativeSlot = nativeSlot;
    }
    public static synchronized void register() {
        if (!registered) {
            Containers.register(OilPotBlockEntity.class, (level, pot) -> pot.createHandler() instanceof IItemHandlerModifiable handler
                ? Optional.of(new PlacedOilStore(pot, handler)) : Optional.empty());
            registered = true;
        }
    }
    public int getContainerSize() { return nativeSlot.getSlots(); }
    public boolean isEmpty() { return nativeSlot.getStackInSlot(0).isEmpty(); }
    public int getMaxStackSize() { return nativeSlot.getSlotLimit(0); }
    @Override public int getMaxStackSize(ItemStack stack) { return nativeSlot.isItemValid(0, stack) ? nativeSlot.getSlotLimit(0) : 0; }
    public ItemStack getItem(int slot) { return nativeSlot.getStackInSlot(slot); }
    public ItemStack removeItem(int slot, int amount) { return nativeSlot.extractItem(slot, amount, false); }
    public ItemStack removeItemNoUpdate(int slot) { return nativeSlot.extractItem(slot, Integer.MAX_VALUE, false); }
    public void setItem(int slot, ItemStack stack) {
        if (!stack.isEmpty() && (!nativeSlot.isItemValid(slot, stack) || stack.getCount() > nativeSlot.getSlotLimit(slot))) {
            throw new IllegalArgumentException("the native oil pot rejects this stack");
        }
        nativeSlot.setStackInSlot(slot, stack);
    }
    public boolean canPlaceItem(int slot, ItemStack stack) { return nativeSlot.isItemValid(slot, stack); }
    public void setChanged() {
        // A caller may have changed the live stack reference. Resubmit it so the native count, model and save agree.
        nativeSlot.setStackInSlot(0, nativeSlot.getStackInSlot(0));
        pot.setChanged();
    }
    public boolean stillValid(Player player) { return Container.stillValidBlockEntity(pot, player); }
    public void clearContent() { nativeSlot.setStackInSlot(0, ItemStack.EMPTY); }
}
