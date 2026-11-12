package io.github.izakyl.folkways.core.resident.internal;

import io.github.izakyl.folkways.core.base.persist.Required;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.KitProvider;
import io.github.izakyl.folkways.core.resident.api.KitProviders;
import io.github.izakyl.folkways.core.resident.api.Pack;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Eighteen native cells followed by the actual inventories of every effective storage item. */
public final class ResidentPack implements Pack {
    public static final int DEFAULT_SIZE = Pack.DEFAULT_SIZE;
    public static final int BASE_PLANNABLE_CELLS = DEFAULT_SIZE;
    private SimpleContainer container;
    private final int baseSize;
    private final List<ItemStack> recovered = new ArrayList<>();
    private Body owner;
    private Runnable changed = () -> { };

    public ResidentPack() {
        this(DEFAULT_SIZE);
    }

    public ResidentPack(int size) {
        baseSize = size;
        container = new SimpleContainer(size);
    }

    @Override
    public void onChanged(Runnable listener) {
        changed = Objects.requireNonNull(listener);
    }

    @Override
    public void bind(Body body) {
        owner = body;
        if (!recovered.isEmpty() && body.mob().isAddedToLevel()) {
            List<ItemStack> pending = List.copyOf(recovered);
            recovered.clear();
            for (ItemStack stack : pending) {
                ItemStack rest = insert(stack);
                if (!rest.isEmpty()) {
                    body.mob().spawnAtLocation(rest);
                }
            }
        }
    }

    @Override
    public void applyCapacityMultiplier(double multiplier) {
        int size = (int) Math.max(baseSize, Math.round(baseSize * multiplier));
        if (size == container.getContainerSize()) {
            return;
        }
        SimpleContainer previous = container;
        container = new SimpleContainer(size);
        for (int slot = 0; slot < previous.getContainerSize(); slot++) {
            ItemStack stack = previous.getItem(slot);
            if (slot < size) {
                container.setItem(slot, stack);
            } else {
                recover(stack);
            }
        }
        changed.run();
    }

    private List<KitProvider.Inventory> extensions() {
        return owner == null ? List.of() : KitProviders.inventories(owner);
    }

    private record Cell(KitProvider.Inventory inventory, int index) {
    }

    private Cell cell(int slot) {
        int index = slot - container.getContainerSize();
        for (var inventory : extensions()) {
            if (index < inventory.getSlots()) {
                return new Cell(inventory, index);
            }
            index -= inventory.getSlots();
        }
        throw new IndexOutOfBoundsException("pack slot " + slot);
    }

    @Override
    public int ownCells() {
        return container.getContainerSize();
    }

    @Override
    public int slotLimit(int slot, ItemStack stack) {
        if (slot < container.getContainerSize()) {
            return container.getMaxStackSize(stack);
        }
        Cell cell = cell(slot);
        return cell.inventory().isItemValid(cell.index(), stack) ? cell.inventory().limit(cell.index(), stack) : 0;
    }

    @Override
    public ItemStack insertSlot(int slot, ItemStack stack, boolean simulate) {
        if (slot >= container.getContainerSize()) {
            Cell cell = cell(slot);
            ItemStack rest = cell.inventory().insertItem(cell.index(), stack, simulate);
            if (!simulate && rest.getCount() != stack.getCount()) {
                changed.run();
            }
            return rest;
        }
        ItemStack there = container.getItem(slot);
        if (!there.isEmpty() && !ItemStack.isSameItemSameComponents(there, stack)) {
            return stack;
        }
        int moved = Math.min(stack.getCount(), Math.max(0, slotLimit(slot, stack) - there.getCount()));
        if (moved > 0 && !simulate) {
            container.setItem(slot, stack.copyWithCount(there.getCount() + moved));
            changed.run();
        }
        return stack.copyWithCount(stack.getCount() - moved);
    }

    @Override
    public ItemStack insert(ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < getContainerSize() && !rest.isEmpty(); slot++) {
                if (getItem(slot).isEmpty() == (pass == 1)) {
                    rest = insertSlot(slot, rest, false);
                }
            }
        }
        return rest;
    }

    @Override
    public int plannableCells(Predicate<Item> tool) {
        int cells = 0;
        for (int slot = 0; slot < getContainerSize(); slot++) {
            ItemStack stack = getItem(slot);
            if (stack.isEmpty() || !tool.test(stack.getItem())) {
                cells++;
            }
        }
        return cells;
    }

    @Override
    public int plannableRoom(Predicate<Item> tool) {
        int cells = 0;
        for (int slot = 0; slot < getContainerSize(); slot++) {
            if (getItem(slot).isEmpty()) {
                cells++;
            }
        }
        return cells;
    }

    @Override
    public int countMatching(Predicate<ItemStack> predicate) {
        return contents().stream().filter(predicate).mapToInt(ItemStack::getCount).sum();
    }

    @Override
    public int count(Item item) {
        return countMatching(stack -> stack.is(item));
    }

    @Override
    public List<ItemStack> contents() {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < getContainerSize(); slot++) {
            ItemStack stack = getItem(slot);
            if (!stack.isEmpty()) {
                result.add(stack.copy());
            }
        }
        return List.copyOf(result);
    }

    @Override
    public int getContainerSize() {
        return container.getContainerSize() + extensions().stream().mapToInt(KitProvider.Inventory::getSlots).sum();
    }

    @Override
    public boolean isEmpty() {
        return contents().isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot < container.getContainerSize()) {
            return container.getItem(slot);
        }
        Cell cell = cell(slot);
        return cell.inventory().getStackInSlot(cell.index());
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result;
        if (slot < container.getContainerSize()) {
            result = container.removeItem(slot, amount);
        } else {
            Cell cell = cell(slot);
            result = cell.inventory().extractItem(cell.index(), amount, false);
        }
        changed.run();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < container.getContainerSize()) {
            // Transfer the whole stack without splitting or copying its component state.
            ItemStack taken = container.removeItemNoUpdate(slot);
            changed.run();
            return taken;
        }
        return removeItem(slot, getItem(slot).getCount());
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < container.getContainerSize()) {
            container.setItem(slot, stack);
        } else {
            Cell cell = cell(slot);
            cell.inventory().setStackInSlot(cell.index(), stack);
        }
        changed.run();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slotLimit(slot, stack) > 0;
    }

    @Override
    public int getMaxStackSize() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void setChanged() {
        container.setChanged();
        // Callers may mutate a live stack (ammunition, food, tools). Notify the native owner so it persists it.
        for (var inventory : extensions()) {
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                if (!inventory.getStackInSlot(slot).isEmpty()) {
                    inventory.setStackInSlot(slot, inventory.getStackInSlot(slot));
                }
            }
        }
        changed.run();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        // Clear extensions before removing base-cell backpacks that make those extensions reachable.
        for (var inventory : extensions()) {
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        container.clearContent();
        changed.run();
    }

    @Override
    public void recover(ItemStack stack) {
        if (!stack.isEmpty()) {
            recovered.add(stack.copy());
        }
    }

    @Override
    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        ListTag items = new ListTag();
        // Worn storage saves its own contents. Serializing the flattened inventory would duplicate them.
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                CompoundTag row = new CompoundTag();
                row.putInt("Slot", slot);
                row.put("Stack", stack.save(provider));
                items.add(row);
            }
        }
        tag.put("Items", items);
        if (!recovered.isEmpty()) {
            ListTag pending = new ListTag();
            recovered.forEach(stack -> pending.add(stack.save(provider)));
            tag.put("Recovered", pending);
        }
        return tag;
    }

    @Override
    public void load(CompoundTag tag, HolderLookup.Provider provider) {
        container.clearContent();
        recovered.clear();
        for (Tag item : Required.list(tag, "Items", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) item;
            int slot = row.contains("Slot", Tag.TAG_BYTE) ? row.getByte("Slot") & 255 : Required.integer(row, "Slot");
            ItemStack stack = ItemStack.parseOptional(provider, Required.compound(row, "Stack"));
            if (slot >= 0 && slot < container.getContainerSize()) {
                container.setItem(slot, stack);
            } else {
                recover(stack);
            }
        }
        for (Tag item : tag.getList("Recovered", Tag.TAG_COMPOUND)) {
            recover(ItemStack.parseOptional(provider, (CompoundTag) item));
        }
        changed.run();
    }

    @Override
    public void dropContents(LivingEntity entity) {
        // External inventories stay inside their worn items when those items drop.
        for (ItemStack stack : container.removeAllItems()) {
            entity.spawnAtLocation(stack);
        }
        recovered.forEach(entity::spawnAtLocation);
        recovered.clear();
        changed.run();
    }
}
