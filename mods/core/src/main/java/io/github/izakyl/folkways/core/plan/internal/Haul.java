package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.Sizing;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.internal.HaulWork;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class Haul {

    private Haul() {
    }

    // A delivery asks for no more than this many at once, however roomy the packs.
    private static final int MOST_AT_ONCE = 64 * 64;

    // A delivery becomes the work its goods end up through, which needs them like any other work: into a store, the
    // put that lays them down there; into a resident's pack, the resident's receipt of them. It carries at most what
    // one pack holds: the rest is asked for again once this much has arrived.
    public static final Refinement DELIVERIES = new Refinement() {
        @Override
        public ResourceLocation id() {
            return HaulWork.DOMAIN;
        }

        @Override
        public Optional<Grown> expand(Intent intent) {
            return grow(intent, Sizing.UNBOUNDED);
        }

        @Override
        public Optional<Change> rewrite(Intent intent, Grown graph, Context context) {
            return grow(intent, context.sizing()).map(Change::of);
        }

        private Optional<Grown> grow(Intent intent, Sizing sizing) {
            if (!(intent instanceof Delivery delivery)) {
                return Optional.empty();
            }
            NodeSpec asked = delivery.spec();
            if (delivery.source().isPresent()) {
                return Optional.of(TransferNode.collecting(delivery));
            }
            long wanted = delivery.goods().count();
            long count = Math.min(wanted, sizing.runs(asked.site(),
                List.of(new Need(delivery.goods().spec(), 1, true)), Vocations.of(Vocations.HAULING),
                Math.min(wanted, MOST_AT_ONCE)));
            if (asked.site() instanceof WorkSite.AtEntity into) {
                return Optional.of(Grown.of(Receipt.of(delivery, into, count)));
            }
            return Optional.of(Grown.of(TransferNode.delivering(asked.id(), new Stash(asked.site().where()),
                new Need(delivery.goods().spec(), count, true), asked.stances(), delivery.arrived())));
        }
    };

}
