package io.github.izakyl.folkways.core.item.api;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * A container that knows better than its slots how goods go in: a resident's pack spreads them over its own cells
 * and the worn inventories bound to it, each by its own rules. {@link Containers#insert} hands it the whole stack.
 */
public interface Inserting extends Container {

    /** Puts in what fits and answers what is left over. */
    ItemStack insert(ItemStack stack);
}
