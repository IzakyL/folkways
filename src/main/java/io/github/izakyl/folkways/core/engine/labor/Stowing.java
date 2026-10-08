package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.List;
import net.minecraft.world.item.ItemStack;

final class Stowing {

    private Stowing() {
    }

    static void put(Worker who, Placement placement, List<ItemStack> made) {
        for (ItemStack stack : made) {
            ItemStack left = stack.copy();
            for (Stash into : placement.into()) {
                if (left.isEmpty()) {
                    break;
                }
                for (Worker.Store store : who.within()) {
                    if (store.at().equals(into.pos())) {
                        left = Containers.insert(store.contents(), left);
                        break;
                    }
                }
            }
            if (!left.isEmpty()) {
                left = Containers.insert(who.pack(), left);
            }
            who.spill(left);
        }
    }
}
