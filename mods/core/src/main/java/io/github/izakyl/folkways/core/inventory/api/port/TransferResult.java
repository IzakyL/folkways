package io.github.izakyl.folkways.core.inventory.api.port;

import java.util.List;

/** The answer to one take or put, simulated or real. */
public sealed interface TransferResult {

    /** Every requested stack moved; for a take, `moved` is what came out. */
    record Done(List<net.minecraft.world.item.ItemStack> moved) implements TransferResult {
        public Done {
            moved = moved.stream().map(net.minecraft.world.item.ItemStack::copy).toList();
        }
    }

    /** Nothing moved. */
    record Refused(Why why) implements TransferResult {
    }

    enum Why {
        NO_ROOM,
        NOTHING_TO_TAKE,
        NOT_ALLOWED,
        UNUSABLE,
        UNAVAILABLE
    }

    default boolean done() {
        return this instanceof Done;
    }
}
