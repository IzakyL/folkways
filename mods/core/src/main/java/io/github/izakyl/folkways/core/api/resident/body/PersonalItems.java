package io.github.izakyl.folkways.core.api.resident.body;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Ownership on the physical stack; no second inventory or saved copy of the item exists. */
public final class PersonalItems {
    private static final String TAG = "folkways:personal_item";
    private static final String RESIDENT = "resident";
    private static final String PURPOSE = "purpose";

    private PersonalItems() {
    }

    public static void mark(Body body, ItemStack actual, String purpose) {
        Objects.requireNonNull(body);
        Objects.requireNonNull(actual);
        if (Objects.requireNonNull(purpose).isBlank()) throw new IllegalArgumentException("empty personal item purpose");
        if (actual.isEmpty()) throw new IllegalArgumentException("cannot mark an empty stack");
        CompoundTag owner = new CompoundTag();
        owner.putUUID(RESIDENT, body.id());
        owner.putString(PURPOSE, purpose);
        CompoundTag data = actual.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.put(TAG, owner);
        actual.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    public static boolean belongsTo(Body body, ItemStack stack) {
        return purpose(body, stack).isPresent();
    }

    /** Personal goods stay outside every worker's material pool, including goods held for someone else. */
    public static boolean marked(ItemStack stack) {
        if (stack.isEmpty()) return false;
        CompoundTag owner = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(TAG);
        return owner.hasUUID(RESIDENT) && !owner.getString(PURPOSE).isBlank();
    }

    public static Optional<String> purpose(Body body, ItemStack stack) {
        Objects.requireNonNull(body);
        Objects.requireNonNull(stack);
        if (stack.isEmpty()) return Optional.empty();
        CompoundTag owner = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(TAG);
        if (!owner.hasUUID(RESIDENT) || !owner.getUUID(RESIDENT).equals(body.id())) return Optional.empty();
        String purpose = owner.getString(PURPOSE);
        return purpose.isBlank() ? Optional.empty() : Optional.of(purpose);
    }

    /** Removes only Folkways ownership, retaining all other custom data and item components. */
    public static void clear(ItemStack actual) {
        Objects.requireNonNull(actual);
        CustomData previous = actual.get(DataComponents.CUSTOM_DATA);
        if (previous == null) return;
        CompoundTag data = previous.copyTag();
        data.remove(TAG);
        if (data.isEmpty()) actual.remove(DataComponents.CUSTOM_DATA);
        else actual.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }
}
