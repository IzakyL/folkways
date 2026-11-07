package io.github.izakyl.folkways.core.resident.api;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public interface Pack extends Container {

    int DEFAULT_SIZE = 18;

    int BASE_PLANNABLE_CELLS = DEFAULT_SIZE;

    void onChanged(Runnable listener);

    void applyCapacityMultiplier(double multiplier);

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
