package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

public interface Worker {

    Resident resident();

    Mob body();

    Container pack();

    // The declared inputs, already paid for by the core. Substitutions expose the requested item only for this
    // commit; inputs() retains the actual payment. Unused inputs are returned as the goods that paid for them.
    default List<ItemStack> supplied() {
        return List.of();
    }

    default List<InputReceipt> inputs() {
        return supplied().stream().map(stack -> new InputReceipt(stack, stack)).toList();
    }

    List<Store> within();

    record Store(WorldPos at, Container contents) {
    }

    ItemStack held(ToolNeed need);

    void spill(ItemStack stack);

    Effects effects();

    List<Node> route();

    Placement placement();
}
