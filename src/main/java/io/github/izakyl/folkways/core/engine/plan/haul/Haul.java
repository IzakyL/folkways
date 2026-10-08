package io.github.izakyl.folkways.core.engine.plan.haul;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.perk.Perk;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.VocationSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Sizing;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.plan.Carrying;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class Haul {

    public static final ResourceLocation DOMAIN =
        ResourceLocation.fromNamespaceAndPath("folkways", "haul");

    public static final String QUICK_HANDS = "hauling_quick_hands";

    public static final VocationSpec SPEC =
        new VocationSpec(Vocations.HAULING, List.of(new Perk(QUICK_HANDS, 4)));

    private static final Workload.Haste HASTE =
        Workload.Haste.ranked(QUICK_HANDS, FolkwaysConfig::haulingHaste);

    private Haul() {
    }

    // How goods move on the plan: the core's rule for every transfer.
    public static final Carrying CARRYING = new Carry();

    // A delivery asks for no more than this many at once, however roomy the packs.
    private static final int MOST_AT_ONCE = 64;

    // A delivery becomes the work its goods end up through, which needs them like any other work: into a store, the
    // put that lays them down there; into a resident's pack, the resident's receipt of them. It carries at most what
    // one pack holds: the rest is asked for again once this much has arrived.
    public static final Refinement DELIVERIES = new Refinement() {
        @Override
        public ResourceLocation id() {
            return DOMAIN;
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

    static Vocation trade() {
        return Vocations.required(Vocations.HAULING);
    }

    public static double haste(Worker who) {
        return HASTE.of(who);
    }
}
