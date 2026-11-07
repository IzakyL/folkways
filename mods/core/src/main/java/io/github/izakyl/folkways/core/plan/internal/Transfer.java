package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.TransportIntent;
import io.github.izakyl.folkways.core.resident.api.Gait;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

// The material bindings of a transport intent: stock, a maker's output or exact carried stacks, and the edge or
// store they feed. The request itself lives only in the intent.
record Transfer(TransportIntent intent, From from, To to) {

    public ItemSpec goods() {
        return intent.goods();
    }

    public long count() {
        return intent.count();
    }

    public sealed interface From {

        record Stocked(Stash where) implements From {
        }

        // What `maker` makes, `yield` in all. Shared goods are for more than one consumer: they are never handed
        // straight on, but left in a store beside the maker, for each consumer to take its share from.
        record Made(UUID maker, long yield, boolean shared) implements From {

            public Made(UUID maker, long yield) {
                this(maker, yield, false);
            }
        }

        // A share of goods made for several consumers, lying in `out` once `suppliers` put them there: the maker
        // itself, or whoever carried them on from it.
        record LeftBy(List<UUID> suppliers, Stash out) implements From {

            public LeftBy {
                suppliers = List.copyOf(suppliers);
            }
        }

        // These very stacks, in the pack of `hand`.
        record Carried(UUID hand, List<ItemStack> stacks) implements From {

            public Carried {
                stacks = stacks.stream().map(ItemStack::copy).toList();
            }
        }
    }

    public sealed interface To {

        record Feeding(Edge edge) implements To {
        }

        record Stored(Stash into) implements To {
        }
    }

    // What carrying costs before it is expanded: the walk as the crow flies, a floor under any real haul. Goods in
    // another realm cost nothing here, since no straight line reaches them.
    public static long walk(WorldPos from, WorkSite to) {
        WorldPos near = to.where();
        if (!from.realm().equals(near.realm())) {
            return 0;
        }
        double blocks = Math.sqrt(from.cell().distSqr(near.cell()));
        return (long) Math.floor(blocks / Gait.BLOCKS_PER_TICK);
    }
}
