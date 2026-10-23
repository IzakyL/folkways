package io.github.izakyl.folkways.cookery.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BottleBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarCabinetBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.init.tag.TagMod;
import com.github.ysbbbbbb.kaleidoscopetavern.item.BottleBlockItem;
import io.github.izakyl.folkways.core.api.terms.Containers;
import java.util.Optional;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** The real two rendered bottles are the inventory; no parallel stock or synthetic display is kept. */
public final class TavernCabinets {
    private static boolean registered;
    private TavernCabinets() { }

    public static synchronized void register() {
        if (registered) return;
        Containers.register(BarCabinetBlockEntity.class, (level, cabinet) -> Optional.of(inventory(cabinet)));
        TavernCounters.supply();
        registered = true;
    }

    public static Container inventory(BarCabinetBlockEntity cabinet) { return new CabinetStore(cabinet); }

    private static boolean bottle(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BottleBlockItem item && item.getBlock() instanceof BottleBlock;
    }

    private static final class CabinetStore implements Container {
        private final BarCabinetBlockEntity cabinet;
        CabinetStore(BarCabinetBlockEntity cabinet) { this.cabinet = cabinet; }
        public int getContainerSize() { return 2; }
        public boolean isEmpty() { return cabinet.getLeftItem().isEmpty() && cabinet.getRightItem().isEmpty(); }
        public int getMaxStackSize() { return 1; }
        public int getMaxStackSize(ItemStack stack) { return bottle(stack) ? 1 : 0; }
        public ItemStack getItem(int slot) {
            return switch (slot) {
                case 0 -> cabinet.getLeftItem();
                case 1 -> cabinet.getRightItem();
                default -> throw new IndexOutOfBoundsException("cabinet slot " + slot);
            };
        }
        public boolean canPlaceItem(int slot, ItemStack stack) {
            if (slot < 0 || slot >= 2 || !bottle(stack)) return false;
            // The native irregular-bottle tag occupies the entire cabinet and renders only on the left.
            if (stack.is(TagMod.BAR_CABINET_IRREGULAR)) return slot == 0 && cabinet.getRightItem().isEmpty();
            return slot != 1 || cabinet.getLeftItem().isEmpty() || !cabinet.getLeftItem().is(TagMod.BAR_CABINET_IRREGULAR);
        }
        public void setItem(int slot, ItemStack stack) {
            if (!stack.isEmpty() && (stack.getCount() != 1 || !canPlaceItem(slot, stack)))
                throw new IllegalArgumentException("cabinet accepts one native bottle per available display position");
            if (slot == 0) cabinet.setLeftItem(stack);
            else if (slot == 1) cabinet.setRightItem(stack);
            else throw new IndexOutOfBoundsException("cabinet slot " + slot);
            setChanged();
        }
        public ItemStack removeItem(int slot, int count) {
            if (count <= 0) return ItemStack.EMPTY;
            ItemStack removed = getItem(slot).split(count);
            if (!removed.isEmpty()) setChanged();
            return removed;
        }
        public ItemStack removeItemNoUpdate(int slot) { return removeItem(slot, Integer.MAX_VALUE); }
        public void setChanged() {
            cabinet.setSingle(!cabinet.getLeftItem().isEmpty() && cabinet.getLeftItem().is(TagMod.BAR_CABINET_IRREGULAR));
            cabinet.setChanged();
            // Inventory has already changed. A notification failure cannot turn a transfer into a refund.
            try { cabinet.refresh(); }
            catch (RuntimeException | LinkageError broken) {
                org.slf4j.LoggerFactory.getLogger("folkways-dining")
                    .warn("tavern cabinet refresh failed after its inventory settled at {}", cabinet.getBlockPos(), broken);
            }
        }
        public boolean stillValid(Player player) { return Container.stillValidBlockEntity(cabinet, player); }
        public void clearContent() {
            cabinet.setLeftItem(ItemStack.EMPTY); cabinet.setRightItem(ItemStack.EMPTY); setChanged();
        }
    }
}
