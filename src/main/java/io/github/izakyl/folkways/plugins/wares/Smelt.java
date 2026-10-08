package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

// One load cooked at a cooker. It is refined into the work it takes - loading the input, loading the fuel that cooks
// it, and taking out what is cooked once both are in - so the fuel is a need on the plan like any other, fetched
// and carried within the schedule, and not left to a chore that turns up once the input sits there cold. Its own
// spec sums that work up, for the plan's costs before it is refined.
record Smelt(NodeSpec spec, WorldPos at, Block block, Stances stances, ItemSpec takes, int load, ItemSpec makes,
             long yield, int cookTicks, Optional<Need> fuel, Runnable out) implements Intent {

    static final Refinement SMELTING = new Refinement() {
        @Override
        public ResourceLocation id() {
            return ResourceLocation.fromNamespaceAndPath("folkways", "smelting");
        }

        @Override
        public Optional<Grown> expand(Intent intent) {
            return intent instanceof Smelt smelt ? Optional.of(smelt.work()) : Optional.empty();
        }
    };

    // `waiting` is how long the cooker is spoken for by the batches booked before this load.
    static Smelt of(UUID id, WorldPos at, Block block, Stances stances, ItemSpec takes, int load, ItemSpec makes, long yield,
                    int cookTicks, long waiting, Optional<Need> fuel, Runnable out) {
        List<Need> needs = new ArrayList<>();
        needs.add(new Need(takes, load));
        fuel.ifPresent(needs::add);
        NodeSpec spec = CraftNode.specOf(id, at, stances,
                Workload.Once.of(Stations.LABOR_TICKS, WaresContent::haste))
            .needs(needs)
            .gives(new Amount(makes, yield))
            .doing(WaresContent.LOADING, takes)
            .estimate((int) Math.min(Integer.MAX_VALUE,
                Stations.LABOR_TICKS * (fuel.isPresent() ? 3 : 2) + (long) cookTicks * load + waiting))
            .done();
        return new Smelt(spec, at, block, stances, takes, load, makes, yield, cookTicks, fuel, out);
    }

    // What is cooked is taken out under the smelt's own id, the last step of its work, and the cooker told so.
    Grown work() {
        Node loading = new LoadNode(at, block, stances, takes, load, cookTicks);
        Node taking = new TakeCookedNode(spec.id(), at, block, stances, makes, yield, out);
        List<Node> nodes = new ArrayList<>(List.of(loading));
        List<Before> links = new ArrayList<>(List.of(new Before(loading.id(), taking.id())));
        fuel.ifPresent(need -> {
            Node stoking = new FuelNode(UUID.randomUUID(), at, block, stances, need.spec(), need.count(), () -> { });
            nodes.add(stoking);
            links.add(new Before(stoking.id(), taking.id()));
        });
        nodes.add(taking);
        return new Grown(nodes, links);
    }
}
