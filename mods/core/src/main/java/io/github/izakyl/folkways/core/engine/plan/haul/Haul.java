package io.github.izakyl.folkways.core.engine.plan.haul;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.VocationSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
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
import io.github.izakyl.folkways.effect.Modifier;
import io.github.izakyl.folkways.effect.Scope;
import io.github.izakyl.folkways.effect.Stats;
import io.github.izakyl.folkways.effect.Stat;
import io.github.izakyl.folkways.effect.perk.Perk;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class Haul {

    public static final ResourceLocation DOMAIN =
        ResourceLocation.fromNamespaceAndPath("folkways", "haul");

    public static final String QUICK_HANDS = "hauling_quick_hands";

    public static final String HEAVY = "hauling_heavy";
    public static final String SWIFT = "hauling_swift";
    public static final String BATCH = "hauling_batch";
    public static final String ENDURANCE = "hauling_endurance";

    public static final Stat TRAVEL_SPEED = new Stat(id("haul_travel_speed"), 1, 1, 3);
    public static final Stat BATCH_SIZE = new Stat(id("haul_batch_size"), 1, 1, 4);

    public static final VocationSpec SPEC =
        new VocationSpec(Vocations.HAULING, List.of(
            new Perk(HEAVY, 3, Perk.Grant.multiplied(Stats.PACK_ROOM, () -> List.of(1.25, 1.5, 2.0))),
            new Perk(SWIFT, 3, Perk.Grant.multiplied(TRAVEL_SPEED, () -> List.of(1.08, 1.16, 1.25))),
            new Perk(QUICK_HANDS, 3, Perk.Grant.haste(Vocations.HAULING, () -> List.of(0.85, 0.72, 0.60))),
            new Perk(BATCH, 3, Perk.Grant.added(BATCH_SIZE, () -> List.of(1, 2, 3))),
            new Perk(ENDURANCE, 3, rank -> List.of(new Modifier.Multiply(Stats.FATIGUE,
                List.of(0.85, 0.70, 0.55).get(rank - 1), Scope.trade(Vocations.HAULING))))));

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }

    private static final Workload.Haste HASTE = Workload.Haste.of(Vocations.HAULING);

    private Haul() {
    }

    // How goods move on the plan: the core's rule for every transfer.
    public static final Carrying CARRYING = new Carry();

    // A delivery asks for no more than this many at once, however roomy the packs.
    private static final int MOST_AT_ONCE = 64 * 64;

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

    static Vocation trade() {
        return Vocations.required(Vocations.HAULING);
    }

    public static double haste(Worker who) {
        return HASTE.of(who);
    }
}
