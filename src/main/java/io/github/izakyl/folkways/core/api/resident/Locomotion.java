package io.github.izakyl.folkways.core.api.resident;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.NodeEvaluator;

public interface Locomotion {

    ResourceLocation id();

    NodeEvaluator newEvaluator(Search search);

    void fit(Mob body);

    Conveyance along(double speed, RefusalKind lost);
}
