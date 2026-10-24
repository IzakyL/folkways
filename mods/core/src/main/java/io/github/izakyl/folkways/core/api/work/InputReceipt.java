package io.github.izakyl.folkways.core.api.work;

import net.minecraft.world.item.ItemStack;

/** The logical input used by work and the real goods paid for it. Never an inventory conversion. */
public record InputReceipt(ItemStack requested, ItemStack paid) {
    public InputReceipt {
        requested = requested.copy();
        paid = paid.copy();
        if (requested.isEmpty() || paid.isEmpty() || paid.getCount() % requested.getCount() != 0) {
            throw new IllegalArgumentException("input receipts need whole units");
        }
    }

    @Override public ItemStack requested() { return requested.copy(); }
    @Override public ItemStack paid() { return paid.copy(); }

    public int cost() { return paid.getCount() / requested.getCount(); }
}
