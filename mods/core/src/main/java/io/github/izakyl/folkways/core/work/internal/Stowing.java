package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.internal.Stores;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

public final class Stowing {

    private Stowing() {
    }

    public static void put(Worker who, Placement placement, List<ItemStack> made) {
        for (ItemStack stack : made) {
            ItemStack left = stack.copy();
            for (Stash into : placement.into()) {
                if (left.isEmpty()) {
                    break;
                }
                for (Worker.Store store : who.within()) {
                    if (store.at().equals(into.pos())) {
                        if (StoreRule.stores(Stores.rulesAt((ServerLevel) who.body().level(), store.at()))) {
                            left = Containers.insert(store.contents(), left);
                            if (who.body().level() instanceof ServerLevel level) {
                                InventoryFeedback.store(level, store.at());
                            }
                        }
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
