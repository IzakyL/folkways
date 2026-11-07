package io.github.izakyl.folkways.core.inventory.api;

import java.util.OptionalInt;
import net.minecraft.world.item.ItemStack;

public final class SlotSnapshot {

    private final long incarnation;
    private final int physicalSlot;
    private final int accessibleSlot;
    private final ItemStack stack;
    private final int stackLimit;

    public SlotSnapshot(int physicalSlot, int accessibleSlot, ItemStack stack, int stackLimit) {
        this(physicalSlot, accessibleSlot, stack, stackLimit, 0);
    }

    public SlotSnapshot(int physicalSlot, int accessibleSlot, ItemStack stack, int stackLimit, long incarnation) {
        this.incarnation = incarnation;
        this.physicalSlot = physicalSlot;
        this.accessibleSlot = accessibleSlot;
        this.stack = stack.copy();
        this.stackLimit = stackLimit;
    }

    public long incarnation() {
        return incarnation;
    }

    public int physicalSlot() {
        return physicalSlot;
    }

    public OptionalInt accessibleSlot() {
        return accessibleSlot < 0 ? OptionalInt.empty() : OptionalInt.of(accessibleSlot);
    }

    public ItemStack stackCopy() {
        return stack.copy();
    }

    public int stackLimit() {
        return stackLimit;
    }
}
