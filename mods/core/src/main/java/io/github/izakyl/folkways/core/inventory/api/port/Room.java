package io.github.izakyl.folkways.core.inventory.api.port;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * How much more a store can take, in a form the planner can share out between different goods. The planner books
 * room against these numbers and must deduct across goods wherever the room is shared.
 */
public sealed interface Room {

    /** Bound cells only take their prototype; each open cell may take any one kind, subject to the same cap. */
    record Slots(List<Cell> bound, long open, Cap cap) implements Room {

        public Slots {
            bound = List.copyOf(bound);
            Objects.requireNonNull(cap, "cap");
        }
    }

    /** Exact remaining room, including partly filled slots and empty cells locked to a prototype. */
    record Cell(ItemStack prototype, long room) {

        public Cell {
            Objects.requireNonNull(prototype, "prototype");
            prototype = prototype.copyWithCount(1);
        }

        @Override
        public ItemStack prototype() {
            return prototype.copy();
        }
    }

    /** Per-cell caps. Long.MAX_VALUE means no absolute cap or no stack-size multiplier cap. */
    record Cap(long absolute, long multiplier, Map<Item, Long> overrides) {

        public Cap(long absolute, long multiplier) {
            this(absolute, multiplier, Map.of());
        }

        public Cap {
            overrides = Map.copyOf(overrides);
        }

        public long limit(ItemStack stack) {
            Long override = overrides.get(stack.getItem());
            if (override != null) {
                return Math.max(0, override);
            }
            long size = stack.getMaxStackSize();
            long scaled = multiplier <= 0 || size <= 0 ? 0
                : multiplier > Long.MAX_VALUE / size ? Long.MAX_VALUE : multiplier * size;
            return Math.max(0, Math.min(absolute, scaled));
        }
    }

    /**
     * Shared item and new-kind room; Long.MAX_VALUE means unbounded. An absent perKey imposes no per-item ceiling.
     * When present, missing concrete keys promise no room. Every put consumes the pool and every key's ceiling,
     * conservatively accounting for native priorities without exposing native objects.
     */
    record Pool(long items, long newKinds, Optional<Map<ItemSpec, Long>> perKey) implements Room {

        public Pool(long items, long newKinds) {
            this(items, newKinds, Optional.empty());
        }

        public Pool {
            perKey = perKey.map(Map::copyOf);
        }
    }

    /** No room at all; also what an unusable store reports. */
    Room NONE = new Slots(List.of(), 0, new Cap(0, 1));
}
