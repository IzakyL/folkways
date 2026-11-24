package io.github.izakyl.folkways.core.resident.api;

import io.github.izakyl.folkways.core.item.api.Inserting;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public interface Pack extends Container, Inserting {

    int DEFAULT_SIZE = 18;

    int BASE_PLANNABLE_CELLS = DEFAULT_SIZE;

    void onChanged(Runnable listener);

    /** Binds native worn inventories for reads without creating state or recovering overflow. */
    void bindInventories(Body owner);

    /** Explicitly binds the owner and returns saved overflow to its pack or the world. */
    void bind(Body owner);

    /** Refreshes base-pack growth from resident perks and returns saved overflow; storage items never grow here. */
    void applyCapacityMultiplier(double multiplier);

    int ownCells();

    int slotLimit(int slot, ItemStack stack);

    ItemStack insertSlot(int slot, ItemStack stack, boolean simulate);

    void recover(ItemStack stack);

    int plannableCells(Predicate<Item> tool);

    int plannableRoom(Predicate<Item> tool);

    int countMatching(Predicate<ItemStack> predicate);

    int count(Item item);

    ItemStack insert(ItemStack stack);

    List<ItemStack> contents();

    CompoundTag save(HolderLookup.Provider provider);

    void load(CompoundTag tag, HolderLookup.Provider provider);

    void dropContents(LivingEntity owner);
}
