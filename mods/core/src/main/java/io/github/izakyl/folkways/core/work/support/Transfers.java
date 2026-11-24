package io.github.izakyl.folkways.core.work.support;

import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

public final class Transfers {

    private Transfers() {
    }

    /** Creates a worker-bound transfer through a private store port resolved at execution time. */
    public static Node obligation(NodeSpec spec, Stash store, List<ItemStack> goods, boolean take,
            UUID worker, BiFunction<ServerLevel, Port.Operation, Optional<ItemPort>> resolver) {
        return TransferNode.obligation(spec, store, goods, take, worker, resolver);
    }
}
