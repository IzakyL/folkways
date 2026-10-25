package io.github.izakyl.folkways.core.api.resident.body;

import io.github.izakyl.folkways.core.api.terms.Containers;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class Pack implements Container {
    // Every cell is for whatever is carried: the tools a resident has and the food it keeps by it take the cells
    // they lie in, and the rest is for goods.
    public static final int DEFAULT_SIZE = 18;

    // The cells a pack is taken to have for goods before anyone's is looked into.
    public static final int BASE_PLANNABLE_CELLS = DEFAULT_SIZE;
    private static final String TAG_ITEMS = "Items";
    private static final String TAG_SLOT = "Slot";
    private static final String TAG_STACK = "Stack";

    private final int baseSize;
    private SimpleContainer container;

    public Pack() {
        this(DEFAULT_SIZE);
    }

    public Pack(int size) {
        this.baseSize = size;
        this.container = new SimpleContainer(size);
    }

    public void applyCapacityMultiplier(double multiplier) {
        ensureSize((int) Math.round(baseSize * Math.max(1.0, multiplier)));
    }

    private void ensureSize(int newSize) {
        if (newSize <= container.getContainerSize()) {
            return;
        }
        SimpleContainer bigger = new SimpleContainer(newSize);
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                bigger.setItem(i, stack);
            }
        }
        this.container = bigger;
    }

    // The cells left for goods by the tools carried.
    public int plannableCells(Predicate<Item> tool) {
        Objects.requireNonNull(tool);
        int tools = 0;
        for (ItemStack stack : contents()) {
            if (tool.test(stack.getItem())) {
                tools++;
            }
        }
        return Math.max(0, getContainerSize() - tools);
    }

    // The cells still free for goods.
    public int plannableRoom(Predicate<Item> tool) {
        Objects.requireNonNull(tool);
        int carrying = 0;
        for (ItemStack stack : contents()) {
            if (!tool.test(stack.getItem())) {
                carrying++;
            }
        }
        return Math.max(0, plannableCells(tool) - carrying);
    }

    public int countMatching(Predicate<ItemStack> predicate) {
        Objects.requireNonNull(predicate);
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public int count(Item item) {
        Objects.requireNonNull(item);
        return countMatching(stack -> stack.is(item));
    }

    public ItemStack insert(ItemStack stack) {
        Objects.requireNonNull(stack);
        return Containers.insert(container, stack);
    }

    public List<ItemStack> contents() {
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                contents.add(stack.copy());
            }
        }
        return List.copyOf(contents);
    }

    @Override
    public int getContainerSize() {
        return container.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return container.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return container.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return container.removeItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return container.removeItemNoUpdate(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        container.setItem(slot, stack);
    }

    @Override
    public int getMaxStackSize() {
        return container.getMaxStackSize();
    }

    @Override
    public void setChanged() {
        container.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }

    @Override
    public void clearContent() {
        container.clearContent();
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        ListTag items = new ListTag();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putByte(TAG_SLOT, (byte)i);
                itemTag.put(TAG_STACK, stack.saveOptional(provider));
                items.add(itemTag);
            }
        }
        tag.put(TAG_ITEMS, items);
        return tag;
    }

    public void load(CompoundTag tag, HolderLookup.Provider provider) {
        container.clearContent();
        ListTag items = tag.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        int maxSlot = -1;
        for (int i = 0; i < items.size(); i++) {
            maxSlot = Math.max(maxSlot, items.getCompound(i).getByte(TAG_SLOT) & 255);
        }
        ensureSize(maxSlot + 1);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemTag = items.getCompound(i);
            int slot = itemTag.getByte(TAG_SLOT) & 255;
            if (slot >= 0 && slot < container.getContainerSize() && itemTag.contains(TAG_STACK, Tag.TAG_COMPOUND)) {
                ItemStack stack = ItemStack.parseOptional(provider, itemTag.getCompound(TAG_STACK));
                if (!stack.isEmpty()) {
                    container.setItem(slot, stack);
                }
            }
        }
    }

    public void dropContents(LivingEntity owner) {
        for (ItemStack stack : container.removeAllItems()) {
            if (!stack.isEmpty()) {
                PersonalItems.clear(stack);
                owner.spawnAtLocation(stack);
            }
        }
    }
}
