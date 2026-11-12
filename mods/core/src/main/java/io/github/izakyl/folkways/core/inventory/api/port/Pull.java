package io.github.izakyl.folkways.core.inventory.api.port;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/** Goods asked of a store: an exact stack, or any goods matching a spec. */
public sealed interface Pull {

    record Exact(ItemStack stack) implements Pull {
        public Exact {
            if (stack.isEmpty()) {
                throw new IllegalArgumentException("nothing to pull");
            }
            stack = stack.copy();
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }

    record Matching(ItemSpec spec, long count) implements Pull {
        public Matching {
            Objects.requireNonNull(spec, "spec");
            if (count < 1) {
                throw new IllegalArgumentException("nothing to pull");
            }
        }
    }
}
