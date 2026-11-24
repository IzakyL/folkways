package io.github.izakyl.folkways.core.facility.api.control;

import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/** A native side effect of moving items through a port. An empty filter covers all items. */
public sealed interface TransformHint {

    record Discard(Optional<ItemStack> filter, Mode mode) implements TransformHint {

        public enum Mode {
            UPGRADE,
            NETWORK_EXCESS
        }
    }

    record Convert(ItemStack from, ItemStack to) implements TransformHint {
    }

    record Source(Optional<ItemStack> item) implements TransformHint {
    }

    record Compacting() implements TransformHint {
    }

    record Conversion() implements TransformHint {
    }

    record UnknownUpgrade(ItemStack upgrade) implements TransformHint {
    }
}
