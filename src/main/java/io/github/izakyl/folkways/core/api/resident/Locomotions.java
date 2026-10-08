package io.github.izakyl.folkways.core.api.resident;

import io.github.izakyl.folkways.core.api.Ledger;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.NodeEvaluator;

// The gaits bodies can move with, declared by the plugins that implement them and found by id by the
// plugins whose residents use them.
public final class Locomotions {

    // Walking on foot. A body the colony has no kind for is surveyed as if it walked.
    public static final ResourceLocation WALKING = ResourceLocation.fromNamespaceAndPath("folkways", "walking");

    private static final Ledger<Locomotion> GAITS = new Ledger<>("a gait", Locomotion::id);

    private static final Map<ResourceLocation, Locomotion> NAMED = new ConcurrentHashMap<>();

    private Locomotions() {
    }

    public static Locomotion register(Locomotion gait) {
        return GAITS.claim(gait);
    }

    public static Optional<Locomotion> of(ResourceLocation id) {
        return GAITS.of(id);
    }

    public static Locomotion required(ResourceLocation id) {
        return GAITS.required(id);
    }

    public static List<Locomotion> all() {
        return GAITS.all();
    }

    // A gait by name, for a resident kind declared before - or by a different plugin than - the gait itself.
    // It resolves on first use, and every kind naming the same gait gets the same one.
    public static Locomotion named(ResourceLocation id) {
        return NAMED.computeIfAbsent(id, Named::new);
    }

    private record Named(ResourceLocation id) implements Locomotion {

        @Override
        public NodeEvaluator newEvaluator(Search search) {
            return required(id).newEvaluator(search);
        }

        @Override
        public void fit(Mob body) {
            required(id).fit(body);
        }

        @Override
        public Conveyance along(double speed, RefusalKind lost) {
            return required(id).along(speed, lost);
        }
    }
}
