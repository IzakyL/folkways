package io.github.izakyl.folkways.core.world.internal;

import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Locomotion;
import io.github.izakyl.folkways.core.resident.api.Locomotions;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;

record Walker(Kind kind, Locomotion locomotion, Mob body) {

    record Kind(ResourceLocation locomotion, int width, int height) {
    }

    // A body the colony has no kind for is surveyed as if it walked, when anything walks at all.
    static Optional<Walker> of(Mob body) {
        return Bodies.of(body).map(Body::locomotion)
            .or(() -> Locomotions.of(Locomotions.WALKING))
            .map(locomotion -> new Walker(new Kind(locomotion.id(), Mth.floor(body.getBbWidth() + 1.0F),
                Mth.floor(body.getBbHeight() + 1.0F)), locomotion, body));
    }
}
