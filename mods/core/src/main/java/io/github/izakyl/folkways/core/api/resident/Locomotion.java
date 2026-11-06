package io.github.izakyl.folkways.core.api.resident;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.phys.Vec3;

public interface Locomotion {

    ResourceLocation id();

    NodeEvaluator newEvaluator(Search search);

    void fit(Mob body);

    default void stand(Mob body) {
    }

    default void stand(Mob body, boolean mountedWork) {
        stand(body);
    }

    default Vec3 feet(Mob body) {
        return body.position();
    }

    Conveyance along(double speed, RefusalKind lost);
}
