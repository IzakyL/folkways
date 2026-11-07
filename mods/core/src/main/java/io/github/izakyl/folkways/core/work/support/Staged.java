package io.github.izakyl.folkways.core.work.support;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

// Work whose inputs are the very stacks the planner chose, at the place it chose, rather than whatever
// matches its needs. The core takes them through here before the work runs and puts them back if it fails.
public interface Staged {

    sealed interface Drawn {
    }

    record Taken(List<ItemStack> goods) implements Drawn {

        public Taken {
            goods = goods.stream().map(ItemStack::copy).toList();
        }
    }

    record Short(RefusalKind why) implements Drawn {
    }

    Drawn draw(ServerLevel level, Worker who);

    void restore(ServerLevel level, Worker who, List<ItemStack> goods);
}
