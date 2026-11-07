package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Settlement;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

// Inventory settlement is offered to holdings; the runner never initiates a draw or stows their products.
public record HoldingSettlement(ServerLevel level, Worker worker) implements Settlement {

    @Override
    public Outcome commit(WorkAction action) {
        return Commit.run(level, action, worker);
    }

    @Override
    public void stow(List<ItemStack> made) {
        Stowing.put(worker, worker.placement(), made);
    }
}
