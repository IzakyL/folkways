package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.Gait;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * The intent of moving goods from where they are to where they are wanted: to a node that needs them, over an edge
 * the plan has just given a source, or into a store. A {@link Carrying} rule refines it, through what the plan lends
 * it, only ever into takes into a pack and puts out of one - or into nothing at all, when the goods can be reached
 * where they lie or handed on as they are made.
 */
public record Transfer(From from, To to, ItemSpec goods, long count) {

    public sealed interface From {

        record Stocked(Stash where) implements From {
        }

        record Made(UUID maker, long yield) implements From {
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
