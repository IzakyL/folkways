package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.plan.Stores;
import io.github.izakyl.folkways.core.engine.batch.BatchExecution;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

final class Stowing {

    private Stowing() {
    }

    static List<BatchExecution.Output> put(Worker who, Placement placement, List<ItemStack> made) {
        List<BatchExecution.Output> placed = new ArrayList<>();
        for (ItemStack stack : made) {
            ItemStack left = stack.copy();
            for (Stash into : placement.into()) {
                if (left.isEmpty()) {
                    break;
                }
                for (Worker.Store store : who.within()) {
                    if (store.at().equals(into.pos())) {
                        if (StoreRule.stores(Stores.rulesAt(store.at()))) {
                            int before = left.getCount();
                            left = Containers.insert(store.contents(), left);
                            int inserted = before - left.getCount();
                            if (inserted > 0) placed.add(new BatchExecution.Output(Optional.of(store.at()), Optional.empty(),
                                Goods.specOf(stack), inserted));
                        }
                        break;
                    }
                }
            }
            if (!left.isEmpty()) {
                int before = left.getCount();
                left = Containers.insert(who.pack(), left);
                int inserted = before - left.getCount();
                if (inserted > 0) placed.add(new BatchExecution.Output(Optional.empty(), Optional.of(who.resident().id()),
                    Goods.specOf(stack), inserted));
            }
            who.spill(left);
        }
        return List.copyOf(placed);
    }
}
