package io.github.izakyl.folkways.core.resident.api;

import io.github.izakyl.folkways.core.base.Ledger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.phys.Vec3;

// The gaits bodies can move with, declared by the plugins that implement them and found by id by the
// plugins whose residents use them.
public final class Locomotions {

    // Walking on foot. A body the colony has no kind for is surveyed as if it walked.
    public static final ResourceLocation WALKING = ResourceLocation.fromNamespaceAndPath("folkways_core", "walking");

    private static final Ledger<Locomotion> GAITS = new Ledger<>("a gait", Locomotion::id);

    private static final Map<ResourceLocation, Locomotion> NAMED = new ConcurrentHashMap<>();

    private static final Map<Mob, Locomotion> OVERRIDES = new WeakHashMap<>();

    public static Locomotion current(Body body) {
        return OVERRIDES.getOrDefault(body.mob(), body.kind().locomotion());
    }

    // Overrides are runtime state: the mod owning the link restores them when that link reloads.
    public static void override(Body body, Locomotion moving) {
        ResourceLocation before = body.locomotion().id();
        if (moving == null) {
            OVERRIDES.remove(body.mob());
        } else {
            OVERRIDES.put(body.mob(), moving);
        }
        if (!before.equals(body.locomotion().id())) {
            body.mob().getNavigation().stop();
            body.locomotion().fit(body.mob());
        }
    }

    public static ResourceLocation routeKind(Body body) {
        ResourceLocation kind = body.kind().id();
        ResourceLocation gait = body.locomotion().id();
        return gait.equals(body.kind().locomotion().id()) ? kind
            : ResourceLocation.fromNamespaceAndPath(kind.getNamespace(),
                kind.getPath() + "/moving/" + gait.getNamespace() + "/" + gait.getPath());
    }

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
        public void stand(Mob body) {
            required(id).stand(body);
        }

        @Override
        public void stand(Mob body, boolean mountedWork) {
            required(id).stand(body, mountedWork);
        }

        @Override
        public Vec3 feet(Mob body) {
            return required(id).feet(body);
        }

        @Override
        public Conveyance along(double speed, RefusalKind lost) {
            return required(id).along(speed, lost);
        }
    }
}
