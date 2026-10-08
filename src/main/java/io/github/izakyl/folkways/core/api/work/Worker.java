package io.github.izakyl.folkways.core.api.work;

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

    // The goods this node declared as needs, already taken by the core from wherever it put them. The work
    // uses them however it likes; any it hands back as unused are returned, the rest are spent.
    default List<ItemStack> supplied() {
        return List.of();
    }

    List<Store> within();

    record Store(WorldPos at, Container contents) {
    }

    ItemStack held(ToolNeed need);

    void spill(ItemStack stack);

    int rankOf(String perk);

    List<Node> route();

    Placement placement();
}
