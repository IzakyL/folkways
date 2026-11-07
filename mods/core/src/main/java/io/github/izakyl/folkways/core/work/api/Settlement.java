package io.github.izakyl.folkways.core.work.api;

import java.util.List;
import net.minecraft.world.item.ItemStack;

// The plan's input draw, commit and unused goods are one settlement; products are stowed once on success.
public interface Settlement {

    Outcome commit(WorkAction action);

    void stow(List<ItemStack> made);
}
